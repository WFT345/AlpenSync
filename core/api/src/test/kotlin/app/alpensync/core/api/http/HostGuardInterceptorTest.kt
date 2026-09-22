// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api.http

import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Host confinement (2026-09 audit, finding M1). Android's
 * networkSecurityConfig can only ban cleartext and swap trust anchors per
 * domain — it CANNOT restrict which hosts an app contacts, so the
 * "requests never leave the configured Proton origin" invariant must live
 * in the OkHttp client itself. These tests pin that invariant:
 *   - a request to any host other than the configured API origin fails
 *     without a single byte reaching the foreign server, and
 *   - a redirect can never walk the stamped x-pm-uid / Authorization / HV
 *     headers off-origin — neither with the factory's redirects-off
 *     default nor if redirect-following were ever re-enabled.
 */
class HostGuardInterceptorTest {

    private lateinit var apiServer: MockWebServer
    private lateinit var foreignServer: MockWebServer
    private lateinit var config: ProtonApiConfig

    // Tokens present so the guard is exercised on a fully-stamped request:
    // the values are test fixtures, not secrets.
    private val session = InMemorySession(uid = "uid-fixture", accessToken = "token-fixture")

    @Before
    fun setUp() {
        apiServer = MockWebServer().also { it.start() }
        foreignServer = MockWebServer().also { it.start() }
        config = ProtonApiConfig(baseUrl = apiServer.url("/").toString())
    }

    @After
    fun tearDown() {
        apiServer.shutdown()
        foreignServer.shutdown()
    }

    private fun factoryClient(): OkHttpClient = OkHttpClientFactory.create(config, session)

    @Test
    fun `request to the configured origin proceeds`() {
        apiServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"Code":1000}"""))

        val response = factoryClient().newCall(Request.Builder().url(apiServer.url("/core/v4/ping")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(1, apiServer.requestCount)
    }

    @Test
    fun `request to a foreign host fails before any byte reaches it`() {
        val call = factoryClient().newCall(Request.Builder().url(foreignServer.url("/exfil")).build())

        assertThrows(DisallowedHostException::class.java) { call.execute() }
        assertEquals("the foreign server must never see the request", 0, foreignServer.requestCount)
    }

    @Test
    fun `redirect off-origin is not followed`() {
        apiServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", foreignServer.url("/exfil").toString()),
        )

        val response = factoryClient()
            .newCall(Request.Builder().url(apiServer.url("/core/v4/contacts")).build())
            .execute()

        // The 3xx surfaces as-is (fail-loud error mapping upstream); the
        // stamped headers never travel to the Location target.
        assertEquals(302, response.code)
        assertEquals(0, foreignServer.requestCount)
    }

    @Test
    fun `network-layer guard blocks redirect follow-ups even with redirects re-enabled`() {
        apiServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", foreignServer.url("/exfil").toString()),
        )
        // Deliberately NOT the factory: redirects on, guard only at the
        // network layer — the registration that sees OkHttp's follow-ups.
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(HostGuardInterceptor(config))
            .followRedirects(true)
            .build()

        val call = client.newCall(Request.Builder().url(apiServer.url("/core/v4/contacts")).build())

        assertThrows(IOException::class.java) { call.execute() }
        assertEquals("the follow-up must die before the foreign server", 0, foreignServer.requestCount)
    }

    @Test
    fun `same host on a different port is still off-origin`() {
        // foreignServer shares the api server's hostname (localhost) and
        // differs only by port — the guard must compare the full origin.
        val call = factoryClient().newCall(Request.Builder().url(foreignServer.url("/")).build())

        assertThrows(DisallowedHostException::class.java) { call.execute() }
        assertEquals(0, foreignServer.requestCount)
    }
}
