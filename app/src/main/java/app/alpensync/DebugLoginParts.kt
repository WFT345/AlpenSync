package app.alpensync

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Shared rendering pieces of the M1 debug login screen, split out of
 * DebugLoginScreen.kt at M2d because that file sits at detekt's 300-line
 * limit (plan Rule 16) — a pure move, no behavior change.
 */

@Composable
internal fun AddressRow(address: AddressLine) {
    Text(
        text = stringResource(
            R.string.address_row,
            address.email,
            stringResource(if (address.send) R.string.yes else R.string.no),
            stringResource(if (address.receive) R.string.yes else R.string.no),
            address.keyCount,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
internal fun TokenProofBlock(proof: TokenProof) {
    Text(text = stringResource(R.string.proof_header), style = MaterialTheme.typography.titleSmall)
    val message = when (proof) {
        is TokenProof.Decrypted -> stringResource(R.string.proof_ok, proof.plaintextBytes)
        TokenProof.NoTokenKey -> stringResource(R.string.proof_no_token)
        is TokenProof.Failed -> stringResource(R.string.proof_failed, proof.exceptionName)
    }
    Text(text = message, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun ProgressBlock(text: String) {
    CircularProgressIndicator()
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun noticeText(notice: LogoutNotice): String = stringResource(
    when (notice) {
        LogoutNotice.SESSION_EXPIRED -> R.string.session_expired_notice
        LogoutNotice.SESSION_INCOMPLETE -> R.string.session_incomplete_notice
    },
)

@Composable
internal fun errorMessage(error: LoginUiState.Error): String {
    val resId = errorMessageResources[error.kind] ?: R.string.error_unknown
    return when (error.kind) {
        // These kinds carry a non-secret detail (reason / code+stage / class name).
        LoginErrorKind.UNKNOWN, LoginErrorKind.SERVER_CODE, LoginErrorKind.UNEXPECTED ->
            stringResource(resId, error.detail ?: "?")
        else -> stringResource(resId)
    }
}

private val errorMessageResources: Map<LoginErrorKind, Int> = mapOf(
    LoginErrorKind.WRONG_CREDENTIALS to R.string.error_wrong_credentials,
    LoginErrorKind.APP_VERSION_REJECTED to R.string.error_appversion,
    LoginErrorKind.INFO_UNREACHABLE to R.string.error_network,
    LoginErrorKind.HUMAN_VERIFICATION to R.string.error_human_verification,
    LoginErrorKind.TWO_PASSWORD_MODE to R.string.error_two_password,
    LoginErrorKind.SECURITY_CHECK to R.string.error_security_check,
    LoginErrorKind.KEY_SETUP to R.string.error_key_setup,
    LoginErrorKind.TOTP to R.string.error_totp,
    LoginErrorKind.INTERNAL_STATE to R.string.error_state,
    LoginErrorKind.SERVER_CODE to R.string.error_server_code,
    LoginErrorKind.UNEXPECTED to R.string.error_unexpected,
    LoginErrorKind.UNKNOWN to R.string.error_unknown,
)
