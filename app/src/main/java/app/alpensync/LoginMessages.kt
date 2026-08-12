package app.alpensync

import app.alpensync.core.api.http.EndpointFamily
import app.alpensync.core.auth.LoginResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Maps the typed login failures of `:core:auth` (`LoginResult.Failed.reason`
 * — a short, non-secret machine string by design) to message kinds the debug
 * screen renders from strings.xml. Kept pure so it is unit-testable offline
 * and so every user-visible string stays a resource.
 */
enum class LoginErrorKind {
    /** The `auth` call was rejected — almost always wrong username/password. */
    WRONG_CREDENTIALS,

    /** Code 5003/5004 — our pinned `x-pm-appversion` aged out (API moved). */
    APP_VERSION_REJECTED,

    /** `auth/info` unreachable or malformed — network failure or API change. */
    INFO_UNREACHABLE,

    /** Code 9001 — manual captcha flow required (ADR 0004 Q3, fail-closed). */
    HUMAN_VERIFICATION,

    /** PasswordMode == 2 — separate mailbox password, unsupported at M1. */
    TWO_PASSWORD_MODE,

    /** Modulus envelope / server proof failed — possible MITM or API move. */
    SECURITY_CHECK,

    /** Login succeeded but key derivation failed; the session was wiped. */
    KEY_SETUP,

    /** The TOTP code was rejected or the 2FA call failed. */
    TOTP,

    /** Orchestrator state-machine hiccup (no session / unexpected state). */
    INTERNAL_STATE,

    /**
     * Proton answered with an HTTP error `Code` this build has no typed path
     * for (LoginResult.ServerError); the detail shows "code N at <stage>".
     */
    SERVER_CODE,

    /** Last-resort containment (containUnexpected) caught a non-cancellation
     * failure; the detail is the exception's class name (never its message). */
    UNEXPECTED,

    /** Anything unmapped — the raw reason string is shown alongside. */
    UNKNOWN,
}

/**
 * Every `reason` SrpLoginOrchestrator can emit, mapped. A wrong password
 * surfaces as "auth_failed" (Proton rejects the bad SRP proof with an HTTP
 * error); it cannot be told apart from a mid-call network drop without
 * parsing server error bodies, so the UI text says both honestly.
 */
fun mapFailureReason(reason: String): LoginErrorKind = when (reason) {
    "appversion_rejected" -> LoginErrorKind.APP_VERSION_REJECTED
    "auth_failed" -> LoginErrorKind.WRONG_CREDENTIALS
    "info_failed" -> LoginErrorKind.INFO_UNREACHABLE
    "modulus_unsigned", "modulus_signature_invalid", "modulus_pin_missing",
    "server_proof_decode_failed", "server_proof_mismatch", "srp_failed",
    -> LoginErrorKind.SECURITY_CHECK
    "key_derivation_failed" -> LoginErrorKind.KEY_SETUP
    "two_factor_rejected", "two_factor_failed" -> LoginErrorKind.TOTP
    "no_session", "unexpected_state" -> LoginErrorKind.INTERNAL_STATE
    else -> LoginErrorKind.UNKNOWN
}

const val TOTP_CODE_LENGTH = 6

/**
 * Accepts exactly six ASCII digits (Proton TOTP), tolerating surrounding
 * whitespace from paste. Returns null for anything else, so the caller can
 * keep the submit button disabled instead of round-tripping a bad code.
 */
fun normalizeTotpCode(input: String): String? {
    val trimmed = input.trim()
    return if (trimmed.length == TOTP_CODE_LENGTH && trimmed.all { it in '0'..'9' }) trimmed else null
}

/**
 * Plan Rules 5/19 (fail closed, never crash): last-resort containment for
 * UI-facing coroutine entry points. ANY non-cancellation failure is handed
 * to [onUnexpected] (which sets an honest Error state) instead of escaping
 * to the caller's coroutine as a crash; CancellationException always
 * propagates. Pure and Android-free so the policy is unit-testable offline.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> containUnexpected(onUnexpected: (Throwable) -> T, step: suspend () -> T): T = try {
    step()
} catch (t: Throwable) {
    if (t is CancellationException) throw t
    onUnexpected(t)
}

/**
 * Maps an orchestrator outcome to the screen state. Pure (no side effects):
 * the caller records Success bookkeeping separately. Lives beside the
 * error-kind mapping so every LoginResult variant's UX is reviewable — and
 * testable offline — in one place.
 */
fun mapLoginResultToState(result: LoginResult): LoginUiState = when (result) {
    is LoginResult.Success -> LoginUiState.LoggedIn(username = result.username, restoredSession = false)
    is LoginResult.TwoFactorRequired -> LoginUiState.NeedsTotp
    is LoginResult.HumanVerificationRequired -> result.challengeToStateOrNull()
        ?: LoginUiState.Error(kind = LoginErrorKind.HUMAN_VERIFICATION)
    is LoginResult.TwoPasswordUnsupported -> LoginUiState.Error(LoginErrorKind.TWO_PASSWORD_MODE)
    is LoginResult.ServerError -> result.toErrorState()
    is LoginResult.Failed -> result.toErrorState()
}

/**
 * Proton's own `ResponseCodes.kt` names 8002 `PASSWORD_WRONG` (protoncore
 * @ 1b87f94, network/domain/.../ResponseCodes.kt:40); it arrives as an HTTP
 * 422 from `auth`. Live test 2 (2026-08-12) hit it — this is the code that
 * previously showed as the bare "code 8002 at AUTH".
 */
const val PROTON_CODE_PASSWORD_WRONG = 8002

private fun LoginResult.ServerError.toErrorState(): LoginUiState.Error =
    if (protonCode == PROTON_CODE_PASSWORD_WRONG) {
        LoginUiState.Error(LoginErrorKind.WRONG_CREDENTIALS)
    } else {
        LoginUiState.Error(
            kind = LoginErrorKind.SERVER_CODE,
            detail = serverCodeDetail(protonCode, endpointFamily),
        )
    }

/** Display detail for a ServerError — "code 2511 at AUTH" (non-secret). */
fun serverCodeDetail(code: Int?, family: EndpointFamily): String = "code ${code ?: "?"} at $family"

private fun LoginResult.Failed.toErrorState(): LoginUiState.Error {
    val kind = mapFailureReason(reason)
    return LoginUiState.Error(
        kind = kind,
        detail = reason.takeIf { kind == LoginErrorKind.UNKNOWN },
    )
}

/**
 * The solvable-challenge subset of a 9001 result: both Details fields
 * present and usable → the in-app sheet state; anything missing → null and
 * the caller falls back to manual instructions (ADR 0004 Q3).
 */
internal fun LoginResult.HumanVerificationRequired.challengeToStateOrNull(): LoginUiState.HumanVerification? {
    val token = verificationToken?.takeIf { it.isNotBlank() } ?: return null
    val methods = verificationMethods?.takeIf { it.isNotEmpty() } ?: return null
    return LoginUiState.HumanVerification(startToken = token, methods = methods)
}
