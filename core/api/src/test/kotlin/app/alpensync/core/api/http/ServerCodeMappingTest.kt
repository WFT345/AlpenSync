// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api.http

import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import app.alpensync.core.api.ProtonApiFactory
import app.alpensync.core.api.dto.InfoRequest
import app.alpensync.core.api.log.SafeLog
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Offline proof for the live-test-1 crash fix: an HTTP error the typed
 * interceptors don't claim must surface as [ProtonServerCodeException] with
 * the body's `Code` preserved — never as a raw retrofit2.HttpException
 * escaping to the UI coroutine. Known codes keep their typed paths.
 */
class ServerCodeMappingTest {

    private lateinit var server: MockWebServer

    private val api by lazy {
        ProtonApiFactory(
            config = ProtonApiConfig(baseUrl = server.url("/").toString()),
            session = InMemorySession(),
        ).api
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun unmapped_422_json_code_is_preserved_with_family_and_logged() {
        enqueue(422, "application/json", """{"Code":2511,"Error":"mystery"}""")
        val events = mutableListOf<Pair<SafeLog.Event, Int?>>()
        val previous = SafeLog.sink
        SafeLog.sink = { event, detail -> events.add(event to detail) }

        val e = try {
            assertThrows(ProtonServerCodeException::class.java) { callInfo() }
        } finally {
            SafeLog.sink = previous
        }
        assertEquals(2511, e.protonCode)
        assertEquals(EndpointFamily.AUTH_INFO, e.endpointFamily)
        assertEquals(422, e.httpStatus)
        assertTrue("SERVER_CODE event with the Code", events.contains(SafeLog.Event.SERVER_CODE to 2511))
    }

    @Test fun non_json_422_yields_the_null_code_variant() {
        enqueue(422, "text/plain", "Bad Gateway")

        val e = assertThrows(ProtonServerCodeException::class.java) { callInfo() }
        assertNull(e.protonCode)
        assertEquals(EndpointFamily.AUTH_INFO, e.endpointFamily)
        assertEquals(422, e.httpStatus)
    }

    @Test fun json_422_without_a_code_field_yields_the_null_code_variant() {
        enqueue(422, "application/json", """{"Error":"no code here"}""")

        val e = assertThrows(ProtonServerCodeException::class.java) { callInfo() }
        assertNull(e.protonCode)
    }

    @Test fun human_verification_code_keeps_its_typed_path() {
        enqueue(422, "application/json", """{"Code":9001,"Error":"human verification required"}""")

        assertThrows(HumanVerificationRequiredException::class.java) { callInfo() }
    }

    @Test fun app_version_rejection_code_keeps_its_typed_path() {
        enqueue(422, "application/json", """{"Code":5003,"Error":"no longer supported"}""")

        assertThrows(AppVersionRejectedException::class.java) { callInfo() }
    }

    private fun callInfo() {
        runBlocking {
            mapServerCodes(EndpointFamily.AUTH_INFO) { api.getInfo(InfoRequest(username = "alice")) }
        }
    }

    private fun enqueue(status: Int, contentType: String, body: String) {
        server.enqueue(
            MockResponse().setResponseCode(status).setBody(body).addHeader("Content-Type", contentType),
        )
    }
}
