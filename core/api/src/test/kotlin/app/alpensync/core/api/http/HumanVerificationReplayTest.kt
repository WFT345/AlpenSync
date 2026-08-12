// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api.http

import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real OkHttp stack through MockWebServer to prove the Code
 * 12087 recovery path (protoncore's `HumanVerificationInvalidHandler`
 * shape): a rejected verification token is dropped and the call replayed
 * ONCE without the HV headers, so a fresh 9001 can open a NEW challenge.
 *
 * Regression origin: live test 2 (2026-08-12) ended on a 12087 that
 * surfaced as a Details-less 9001, which the login screen could not turn
 * into a challenge sheet — a correctly-solved challenge dead-ended on the
 * manual-instructions error.
 */
class HumanVerificationReplayTest {

    private lateinit var server: MockWebServer
    private var clearCalls = 0

    /** Mutable stand-in for the SecretStore-backed production source. */
    private val tokens = object : HumanVerificationTokenSource {
        var token: String? = "solved-token"
        var type: String? = "email"
        override fun token(): String? = token
        override fun tokenType(): String? = type
        override fun clear() {
            clearCalls++
            token = null
            type = null
        }
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        clearCalls = 0
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `12087 clears the rejected token and replays without HV headers`() {
        server.enqueue(json("""{"Code":12087,"Error":"Invalid verification code"}"""))
        server.enqueue(json("""{"Code":1000}"""))

        val response = client().newCall(get()).execute()

        assertEquals(200, response.code)
        assertEquals("""{"Code":1000}""", response.body.string())
        assertEquals("the rejected token must be dropped", 1, clearCalls)
        assertNull(tokens.token())

        val first = server.takeRequest()
        assertEquals("solved-token", first.getHeader(HumanVerificationInterceptor.HV_TOKEN_HEADER))
        assertEquals("email", first.getHeader(HumanVerificationInterceptor.HV_TOKEN_TYPE_HEADER))

        val replay = server.takeRequest()
        assertNull(replay.getHeader(HumanVerificationInterceptor.HV_TOKEN_HEADER))
        assertNull(replay.getHeader(HumanVerificationInterceptor.HV_TOKEN_TYPE_HEADER))
    }

    @Test fun `12087 then a fresh 9001 surfaces a NEW solvable challenge`() {
        server.enqueue(json("""{"Code":12087,"Error":"Invalid verification code"}"""))
        server.enqueue(
            json(
                """{"Code":9001,"Details":{"HumanVerificationMethods":["email"],
                   |"HumanVerificationToken":"fresh-start-token"}}""".trimMargin().replace("\n", ""),
            ),
        )

        val thrown = runCatching { client().newCall(get()).execute() }.exceptionOrNull()

        assertTrue("expected a typed HV challenge, got $thrown", thrown is HumanVerificationRequiredException)
        thrown as HumanVerificationRequiredException
        // The dead-end this test exists for: these were null before the fix,
        // so the screen could not build a sheet and showed manual steps.
        assertNotNull(thrown.verificationToken)
        assertEquals("fresh-start-token", thrown.verificationToken)
        assertEquals(listOf("email"), thrown.verificationMethods)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a second 12087 does not loop and falls back to the detail-less signal`() {
        server.enqueue(json("""{"Code":12087}"""))
        server.enqueue(json("""{"Code":12087}"""))

        val thrown = runCatching { client().newCall(get()).execute() }.exceptionOrNull()

        assertTrue(thrown is HumanVerificationRequiredException)
        thrown as HumanVerificationRequiredException
        assertNull(thrown.verificationToken)
        assertNull(thrown.verificationMethods)
        assertEquals("exactly one replay — never a loop", 2, server.requestCount)
    }

    @Test fun `9001 on a token-less request still throws with its Details untouched`() {
        tokens.token = null
        tokens.type = null
        server.enqueue(
            json(
                """{"Code":9001,"Details":{"HumanVerificationMethods":["captcha"],
                   |"HumanVerificationToken":"start-token"}}""".trimMargin().replace("\n", ""),
            ),
        )

        val thrown = runCatching { client().newCall(get()).execute() }.exceptionOrNull()

        thrown as HumanVerificationRequiredException
        assertEquals("start-token", thrown.verificationToken)
        assertEquals(listOf("captcha"), thrown.verificationMethods)
        assertEquals("no replay for a plain 9001", 1, server.requestCount)
        assertEquals(0, clearCalls)
    }

    @Test fun `9001 answering a token-carrying request clears that stale token`() {
        server.enqueue(json("""{"Code":9001}"""))

        runCatching { client().newCall(get()).execute() }

        assertEquals(1, clearCalls)
        assertEquals("no replay for a 9001 — the caller re-prompts", 1, server.requestCount)
    }

    @Test fun `an ordinary response passes through untouched`() {
        server.enqueue(json("""{"Code":1000,"User":{"ID":"u"}}"""))

        val response = client().newCall(get()).execute()

        assertEquals(200, response.code)
        assertEquals(0, clearCalls)
        assertEquals("solved-token", tokens.token())
    }

    private fun client(): OkHttpClient = OkHttpClientFactory.create(
        config = ProtonApiConfig(baseUrl = server.url("/").toString()),
        session = InMemorySession(uid = "uid-1", accessToken = "access-1"),
        humanVerificationTokens = tokens,
    )

    private fun get() = Request.Builder().url(server.url("/core/v4/auth")).build()

    private fun json(body: String) = MockResponse().setBody(body)
        .addHeader("Content-Type", "application/json")
}
