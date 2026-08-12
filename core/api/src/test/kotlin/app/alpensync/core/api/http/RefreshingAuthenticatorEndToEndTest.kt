// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/test/.../http/RefreshingAuthenticatorEndToEndTest.kt
// Added: invalid-grant → onSessionInvalid wipe; 9001-on-refresh propagation.

package app.alpensync.core.api.http

import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import app.alpensync.core.api.ProtonApiFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real OkHttp client through MockWebServer to prove the
 * 401 → refresh → replay wiring, the bounded retry, the recursion guard,
 * and the invalid-grant wipe.
 */
class RefreshingAuthenticatorEndToEndTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private val persisted = mutableListOf<Pair<String, String>>()
    private var sessionInvalidCalls = 0

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession(uid = "uid-1", accessToken = "stale-access")
        persisted.clear()
        sessionInvalidCalls = 0
    }

    @After fun tearDown() = server.shutdown()

    @Test fun authenticated_call_with_401_refreshes_then_retries_and_persists_new_tokens() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"Code":401,"Error":"expired"}"""))
        server.enqueue(json(refreshBody("fresh-access", "fresh-refresh")))
        server.enqueue(json("""{"Code":1000,"User":{"ID":"user-1","Keys":[]}}"""))

        val apis = newFactory()
        val response = apis.api.getUser()

        assertEquals("user-1", response.user.id)
        assertEquals("fresh-access", session.accessToken())
        assertEquals(listOf("fresh-access" to "fresh-refresh"), persisted)

        // Wire order: /users (401), /auth/refresh (200), /users (200).
        val first = server.takeRequest()
        assertEquals("/core/v4/users", first.path)
        assertEquals("Bearer stale-access", first.getHeader("Authorization"))

        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("\"RefreshToken\":\"stored-refresh\""))

        val retry = server.takeRequest()
        assertEquals("/core/v4/users", retry.path)
        assertEquals("Bearer fresh-access", retry.getHeader("Authorization"))
    }

    @Test fun second_401_in_a_row_gives_up_no_third_attempt() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json(refreshBody("fresh-access", "fresh-refresh")))
        server.enqueue(MockResponse().setResponseCode(401))

        val apis = newFactory()
        val ex = runCatching { apis.api.getUser() }.exceptionOrNull()
        assertTrue("expected the 401 to propagate, got $ex", ex != null)
        assertEquals("no infinite refresh loop", 3, server.requestCount)
    }

    @Test fun already_rotated_token_is_reused_without_a_second_refresh_call() = runTest {
        // Simulates the single-flight race: our request goes out with the
        // stale bearer, but by the time the 401 comes back another caller
        // has already refreshed the session. The authenticator must replay
        // with that rotated token and must NOT fire a second refresh.
        server.dispatcher = object : Dispatcher() {
            private var firstUsersCall = true
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/core/v4/users" && firstUsersCall -> {
                    firstUsersCall = false
                    // The "other caller" refreshes while we are in flight.
                    session.update(uid = "uid-1", accessToken = "rotated-by-other-caller")
                    MockResponse().setResponseCode(401)
                }
                request.path == "/core/v4/users" ->
                    json("""{"Code":1000,"User":{"ID":"user-2","Keys":[]}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val apis = newFactory()
        val response = apis.api.getUser()

        assertEquals("user-2", response.user.id)
        assertEquals(2, server.requestCount)
        assertTrue("no refresh call may fire", persisted.isEmpty())

        val first = server.takeRequest()
        assertEquals("Bearer stale-access", first.getHeader("Authorization"))
        val retry = server.takeRequest()
        assertEquals("Bearer rotated-by-other-caller", retry.getHeader("Authorization"))
    }

    @Test fun invalid_grant_on_refresh_wipes_session_state() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        // The refresh call itself is rejected with 401 → invalid grant.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"Code":401,"Error":"invalid grant"}"""))

        val apis = newFactory()
        val ex = runCatching { apis.api.getUser() }.exceptionOrNull()

        assertTrue("expected the 401 to propagate, got $ex", ex != null)
        assertEquals("invalid grant must fire the wipe callback", 1, sessionInvalidCalls)
        // Exactly 2 requests: the 401'd call + the rejected refresh. No replay.
        assertEquals(2, server.requestCount)
    }

    @Test fun human_verification_on_refresh_propagates_typed_error() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            json("""{"Code":9001,"Error":"human verification needed"}""").setResponseCode(422),
        )

        val apis = newFactory()
        val ex = runCatching { apis.api.getUser() }.exceptionOrNull()

        assertTrue(
            "9001 on refresh must surface as HumanVerificationRequiredException, got $ex",
            ex is HumanVerificationRequiredException,
        )
        assertEquals(0, sessionInvalidCalls)
    }

    private fun newFactory(): ProtonApiFactory = ProtonApiFactory(
        config = ProtonApiConfig(baseUrl = server.url("/").toString()),
        session = session,
        refreshConfig = ProtonApiFactory.RefreshConfig(
            mutableSession = session,
            getRefreshToken = { "stored-refresh" },
            onTokensRefreshed = { a, r -> persisted += a to r },
            onSessionInvalid = { sessionInvalidCalls++ },
        ),
    )

    private fun refreshBody(access: String, refresh: String) =
        """{"AccessToken":"$access","RefreshToken":"$refresh","TokenType":"Bearer",
            |"ExpiresIn":86400,"UID":"uid-1","Code":1000}""".trimMargin().replace("\n", "")

    private fun json(body: String) = MockResponse().setBody(body)
        .addHeader("Content-Type", "application/json")
}
