package app.alpensync

import app.alpensync.core.api.http.EndpointFamily
import app.alpensync.core.auth.LoginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginMessagesTest {

    @Test fun every_orchestrator_failure_reason_is_mapped() {
        // Kept in sync with the LoginResult.Failed reasons SrpLoginOrchestrator
        // can emit; a new reason that falls through to UNKNOWN fails here.
        val reasons = listOf(
            "appversion_rejected", "info_failed", "auth_failed",
            "modulus_unsigned", "modulus_signature_invalid", "modulus_pin_missing",
            "srp_failed", "server_proof_decode_failed", "server_proof_mismatch",
            "key_derivation_failed", "two_factor_failed", "two_factor_rejected",
            "no_session", "unexpected_state",
        )
        reasons.forEach { reason ->
            assertNotEquals("$reason must not fall through", LoginErrorKind.UNKNOWN, mapFailureReason(reason))
        }
    }

    @Test fun specific_reason_mappings() {
        assertEquals(LoginErrorKind.APP_VERSION_REJECTED, mapFailureReason("appversion_rejected"))
        assertEquals(LoginErrorKind.WRONG_CREDENTIALS, mapFailureReason("auth_failed"))
        assertEquals(LoginErrorKind.INFO_UNREACHABLE, mapFailureReason("info_failed"))
        assertEquals(LoginErrorKind.SECURITY_CHECK, mapFailureReason("modulus_signature_invalid"))
        assertEquals(LoginErrorKind.SECURITY_CHECK, mapFailureReason("server_proof_mismatch"))
        assertEquals(LoginErrorKind.KEY_SETUP, mapFailureReason("key_derivation_failed"))
        assertEquals(LoginErrorKind.TOTP, mapFailureReason("two_factor_rejected"))
        assertEquals(LoginErrorKind.INTERNAL_STATE, mapFailureReason("unexpected_state"))
    }

    @Test fun unseen_reason_maps_to_unknown() {
        assertEquals(LoginErrorKind.UNKNOWN, mapFailureReason("some_future_reason"))
    }

    @Test fun totp_accepts_six_ascii_digits() {
        assertEquals("123456", normalizeTotpCode("123456"))
        assertEquals("000000", normalizeTotpCode(" 000000 "))
    }

    @Test fun totp_rejects_bad_input() {
        assertNull(normalizeTotpCode(""))
        assertNull(normalizeTotpCode("12345"))
        assertNull(normalizeTotpCode("1234567"))
        assertNull(normalizeTotpCode("12345a"))
        assertNull(normalizeTotpCode("１２３４５６")) // full-width digits are not ASCII digits
    }

    @Test fun server_error_state_shows_the_code_and_stage_honestly() {
        val state = mapLoginResultToState(
            LoginResult.ServerError(protonCode = 2511, endpointFamily = EndpointFamily.AUTH),
        )
        assertTrue(state is LoginUiState.Error)
        state as LoginUiState.Error
        assertEquals(LoginErrorKind.SERVER_CODE, state.kind)
        assertEquals("code 2511 at AUTH", state.detail)
    }

    @Test fun code_8002_reads_as_wrong_credentials_not_a_raw_code() {
        // protoncore names 8002 PASSWORD_WRONG; it arrives as HTTP 422 from
        // `auth` and used to render as the opaque "code 8002 at AUTH".
        val state = mapLoginResultToState(
            LoginResult.ServerError(protonCode = PROTON_CODE_PASSWORD_WRONG, endpointFamily = EndpointFamily.AUTH),
        )
        assertTrue(state is LoginUiState.Error)
        state as LoginUiState.Error
        assertEquals(LoginErrorKind.WRONG_CREDENTIALS, state.kind)
        assertNull("no raw code for a cause we can name", state.detail)
    }

    @Test fun `8002 from a non-auth stage still reads as wrong credentials`() {
        // The code means the same thing wherever it surfaces; the stage adds
        // nothing the user can act on.
        val state = mapLoginResultToState(
            LoginResult.ServerError(PROTON_CODE_PASSWORD_WRONG, EndpointFamily.AUTH_2FA),
        )
        assertEquals(LoginErrorKind.WRONG_CREDENTIALS, (state as LoginUiState.Error).kind)
    }

    @Test fun server_error_without_a_code_says_so_honestly() {
        val state = mapLoginResultToState(LoginResult.ServerError(null, EndpointFamily.USERS))
        assertEquals("code ? at USERS", (state as LoginUiState.Error).detail)
    }

    @Test fun hv_result_without_details_falls_back_to_manual_instructions() {
        val state = mapLoginResultToState(LoginResult.HumanVerificationRequired(null, null))
        assertEquals(LoginUiState.Error(kind = LoginErrorKind.HUMAN_VERIFICATION), state)
    }

    @Test fun solvable_hv_challenge_maps_to_the_sheet_state() {
        val state = mapLoginResultToState(LoginResult.HumanVerificationRequired("tok", listOf("captcha")))
        assertEquals(LoginUiState.HumanVerification(startToken = "tok", methods = listOf("captcha")), state)
    }
}
