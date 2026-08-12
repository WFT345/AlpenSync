// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/sync/src/test/kotlin/io/pcontacts/core/sync/auth/SrpLoginOrchestratorTest.kt
// (rewritten to the M1 surface; same fixture strategy: MockWebServer + real
// JSON + real SRP/bcrypt math; only the two signature seams are injected —
// they are covered by real crypto in ProtonModulusVerifierTest/SrpClientTest,
// and a live Proton-signed modulus cannot be produced offline).

package app.alpensync.core.auth

import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import app.alpensync.core.api.ProtonApiFactory
import app.alpensync.core.api.http.EndpointFamily
import app.alpensync.core.auth.srp.ProtonModulusVerification
import app.alpensync.core.auth.srp.ProtonModulusVerifier
import app.alpensync.core.auth.srp.SrpClient
import app.alpensync.core.auth.store.InMemorySecretStore
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SrpLoginOrchestratorTest {

    /** Test seam: the envelope decode runs for real; the signature check is
     * injected because no live Proton-signed modulus exists offline. */
    private val acceptAnyModulus = ProtonModulusVerifier { _, _ -> ProtonModulusVerification.VALID }

    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySecretStore
    private lateinit var session: InMemorySession

    // A deterministic 256-byte modulus pattern (LE wire order) so the SRP
    // math runs for real without embedding a giant constant.
    private fun modulusLeBytes(): ByteArray = ByteArray(256) { (0xF0 - (it % 7)).toByte() }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = InMemorySecretStore()
        session = InMemorySession()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun happy_path_no_2fa_persists_session_and_derives_key_password() = runTest {
        enqueueInfo()
        enqueueAuth(passwordMode = 1, twoFactor = 0, serverProof = "AAAA")
        enqueueUserAndSalts()

        val orchestrator = newOrchestrator(acceptAnyProof = true)
        val password = "test-password".toCharArray()
        val result = orchestrator.login("alice@example.com", password)

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        assertEquals("uid-1", store.uid())
        assertEquals("access-token-1", store.accessToken())
        assertEquals("refresh-token-1", store.refreshToken())
        val keyPassword = store.keyPassword()
        assertNotNull("keyPassword must be persisted", keyPassword)
        assertEquals("bcrypt trailing hash is 31 chars", 31, keyPassword!!.size)
        assertTrue("password array must be zeroed after login", password.all { it == '\u0000' })

        // The auth request carried the SRP session + client proof + empty Payload.
        server.takeRequest() // auth/info
        val authReq = server.takeRequest()
        val body = authReq.body.readUtf8()
        assertTrue(body.contains("\"SRPSession\":\"srp-session-1\""))
        assertTrue(body.contains("\"ClientProof\""))
        assertTrue(body.contains("\"Payload\":{}"))
    }

    @Test fun two_password_account_fails_loud_and_wipes_session() = runTest {
        enqueueInfo()
        enqueueAuth(passwordMode = 2, twoFactor = 0, serverProof = "AAAA")

        val orchestrator = newOrchestrator(acceptAnyProof = true)
        val result = orchestrator.login("bob@example.com", "pw".toCharArray())

        assertTrue("expected TwoPasswordUnsupported, got $result", result is LoginResult.TwoPasswordUnsupported)
        assertNull("session must be wiped on PasswordMode==2", store.uid())
        assertNull(store.accessToken())
        assertNull(session.accessToken())
        assertNull("no keyPassword may be derived", store.keyPassword())
    }

    @Test fun totp_flow_elevates_existing_session_without_new_tokens() = runTest {
        enqueueInfo()
        enqueueAuth(passwordMode = 1, twoFactor = 1, serverProof = "AAAA")

        val orchestrator = newOrchestrator(acceptAnyProof = true)
        val first = orchestrator.login("carol@example.com", "pw".toCharArray())
        assertTrue(first is LoginResult.TwoFactorRequired)

        server.enqueue(json("""{"Code":1000,"Scopes":["full"]}"""))
        enqueueUserAndSalts()

        val second = orchestrator.submitTwoFactorCode("123456")
        assertTrue("expected Success, got $second", second is LoginResult.Success)
        // No new tokens were issued — the original ones elevated.
        assertEquals("access-token-1", store.accessToken())
        assertNotNull(store.keyPassword())

        // Requests so far: info, auth, 2fa, users, salts.
        server.takeRequest() // auth/info
        server.takeRequest() // auth
        val twoFaReq = server.takeRequest()
        assertTrue(twoFaReq.path!!.endsWith("core/v4/auth/2fa"))
        assertTrue(twoFaReq.body.readUtf8().contains("\"TwoFactorCode\":\"123456\""))
    }

    @Test fun totp_rejection_is_typed_and_keeps_no_stash() = runTest {
        enqueueInfo()
        enqueueAuth(passwordMode = 1, twoFactor = 1, serverProof = "AAAA")
        server.enqueue(json("""{"Code":8002,"Error":"wrong code"}"""))

        val orchestrator = newOrchestrator(acceptAnyProof = true)
        orchestrator.login("dan@example.com", "pw".toCharArray())
        val result = orchestrator.submitTwoFactorCode("000000")

        assertTrue(result is LoginResult.Failed)
        assertEquals("two_factor_rejected", (result as LoginResult.Failed).reason)
        assertNull(store.keyPassword())
    }

    @Test fun server_proof_mismatch_aborts_before_trusting_session() = runTest {
        enqueueInfo()
        // ServerProof of zeros can never equal the computed M2 — the REAL
        // constant-time verifier runs here (no injection).
        enqueueAuth(passwordMode = 1, twoFactor = 0, serverProof = Base64.getEncoder().encodeToString(ByteArray(256)))

        val orchestrator = newOrchestrator(acceptAnyProof = false)
        val result = orchestrator.login("eve@example.com", "pw".toCharArray())

        assertTrue(result is LoginResult.Failed)
        assertEquals("server_proof_mismatch", (result as LoginResult.Failed).reason)
        assertNull(store.uid())
    }

    @Test fun modulus_without_envelope_fails_closed() = runTest {
        // Raw base64 modulus, no OpenPGP envelope → abort before any auth call.
        val rawModulus = Base64.getEncoder().encodeToString(modulusLeBytes())
        server.enqueue(
            json(
                """{"Code":1000,"Modulus":"$rawModulus","ServerEphemeral":"${serverEphemeralB64()}",
                |"Version":4,"Salt":"${saltB64()}","SRPSession":"srp-session-1"}"""
                    .trimMargin().replace("\n", ""),
            ),
        )

        val orchestrator = newOrchestrator(acceptAnyProof = false)
        val result = orchestrator.login("mallory@example.com", "pw".toCharArray())

        assertTrue(result is LoginResult.Failed)
        assertEquals("modulus_unsigned", (result as LoginResult.Failed).reason)
        assertEquals("no /auth call may happen", 1, server.requestCount)
    }

    // --- live test 1 regression: unmapped server codes ---

    @Test fun unmapped_422_on_auth_is_contained_as_server_error_with_code() = runTest {
        enqueueInfo()
        // The live-test-1 shape: a post-HV 422 whose Code has no typed path.
        server.enqueue(error422("""{"Code":2511,"Error":"unmapped"}"""))

        val orchestrator = newOrchestrator(acceptAnyProof = true)
        val result = orchestrator.login("frank@example.com", "pw".toCharArray())

        assertTrue("expected ServerError, got $result", result is LoginResult.ServerError)
        result as LoginResult.ServerError
        assertEquals("the Code must be preserved, not lost", 2511, result.protonCode)
        assertEquals(EndpointFamily.AUTH, result.endpointFamily)
        assertNull("no session may persist", store.uid())
    }

    @Test fun non_json_422_on_auth_info_yields_null_code_server_error() = runTest {
        server.enqueue(error422("Bad Gateway", contentType = "text/plain"))

        val orchestrator = newOrchestrator(acceptAnyProof = true)
        val result = orchestrator.login("gina@example.com", "pw".toCharArray())

        assertTrue("expected ServerError, got $result", result is LoginResult.ServerError)
        result as LoginResult.ServerError
        assertNull(result.protonCode)
        assertEquals(EndpointFamily.AUTH_INFO, result.endpointFamily)
    }

    @Test fun unmapped_422_on_2fa_maps_to_totp_rejection_not_a_crash() = runTest {
        enqueueInfo()
        enqueueAuth(passwordMode = 1, twoFactor = 1, serverProof = "AAAA")
        // A wrong/expired TOTP answered with an HTTP error instead of a 200.
        server.enqueue(error422("""{"Code":8002,"Error":"wrong code"}"""))

        val orchestrator = newOrchestrator(acceptAnyProof = true)
        orchestrator.login("heidi@example.com", "pw".toCharArray())
        val result = orchestrator.submitTwoFactorCode("000000")

        assertTrue("expected Failed, got $result", result is LoginResult.Failed)
        assertEquals("two_factor_rejected", (result as LoginResult.Failed).reason)
        assertNull(store.keyPassword())
    }

    // --- fixtures + helpers ---

    private fun newOrchestrator(acceptAnyProof: Boolean): SrpLoginOrchestrator {
        val api = ProtonApiFactory(
            config = ProtonApiConfig(baseUrl = server.url("/").toString()),
            session = session,
        ).api
        return SrpLoginOrchestrator(
            api = api,
            srp = SrpClient(),
            secretStore = store,
            session = session,
            serverProofVerifier = if (acceptAnyProof) {
                { _, _ -> true }
            } else {
                SrpClient()::verifyServerProof
            },
            modulusVerifier = acceptAnyModulus,
        )
    }

    private fun serverEphemeralB64(): String =
        Base64.getEncoder().encodeToString(ByteArray(256) { (0x11 + (it % 5)).toByte() })

    private fun saltB64(): String =
        Base64.getEncoder().encodeToString(ByteArray(16) { (it * 7 + 1).toByte() })

    private fun envelopeModulusB64(): String {
        val inner = Base64.getEncoder().encodeToString(modulusLeBytes())
        return "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\n$inner\n" +
            "-----BEGIN PGP SIGNATURE-----\n\noffline-test-signature\n=AAAA\n-----END PGP SIGNATURE-----"
    }

    private fun enqueueInfo() {
        server.enqueue(
            json(
                """{"Code":1000,"Modulus":${quote(envelopeModulusB64())},
                |"ServerEphemeral":"${serverEphemeralB64()}","Version":4,
                |"Salt":"${saltB64()}","SRPSession":"srp-session-1"}"""
                    .trimMargin().replace("\n", ""),
            ),
        )
    }

    private fun enqueueAuth(passwordMode: Int, twoFactor: Int, serverProof: String) {
        server.enqueue(
            json(
                """{"Code":1000,"AccessToken":"access-token-1","RefreshToken":"refresh-token-1",
                |"TokenType":"Bearer","ExpiresIn":86400,"UID":"uid-1","UserID":"user-1",
                |"PasswordMode":$passwordMode,"TwoFactor":$twoFactor,
                |"ServerProof":"$serverProof","Scopes":["self"]}"""
                    .trimMargin().replace("\n", ""),
            ),
        )
    }

    private fun enqueueUserAndSalts() {
        server.enqueue(
            json(
                """{"Code":1000,"User":{"ID":"user-1","Keys":[
                |{"ID":"key-1","Version":3,"Primary":1,"Active":1,"PrivateKey":"not-parsed-at-login"}]}}"""
                    .trimMargin().replace("\n", ""),
            ),
        )
        server.enqueue(
            json("""{"Code":1000,"KeySalts":[{"ID":"key-1","KeySalt":"${saltB64()}"}]}"""),
        )
    }

    private fun json(body: String) = MockResponse().setBody(body)
        .addHeader("Content-Type", "application/json")

    private fun error422(body: String, contentType: String = "application/json") =
        MockResponse().setResponseCode(422).setBody(body).addHeader("Content-Type", contentType)

    /** JSON-escape the envelope (it contains newlines). */
    private fun quote(raw: String): String = buildString {
        append('"')
        for (c in raw) {
            when (c) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
        append('"')
    }
}
