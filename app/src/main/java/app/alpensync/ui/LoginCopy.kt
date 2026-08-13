package app.alpensync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.alpensync.LoginErrorKind
import app.alpensync.LoginUiState
import app.alpensync.LogoutNotice
import app.alpensync.R
import app.alpensync.SyncErrorKind

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
        LoginErrorKind.UNKNOWN, LoginErrorKind.SERVER_CODE, LoginErrorKind.UNEXPECTED ->
            stringResource(resId, error.detail ?: "?")
        else -> stringResource(resId)
    }
}

@Composable
internal fun syncErrorText(kind: SyncErrorKind): String = stringResource(
    when (kind) {
        SyncErrorKind.NO_SESSION -> R.string.sync_error_no_session
        SyncErrorKind.HUMAN_VERIFICATION -> R.string.sync_error_human_verification
        SyncErrorKind.APP_VERSION_REJECTED -> R.string.sync_error_appversion
        SyncErrorKind.KEY_UNLOCK -> R.string.sync_error_key_unlock
        SyncErrorKind.NETWORK -> R.string.sync_error_network
        SyncErrorKind.UNEXPECTED -> R.string.sync_error_unexpected
    },
)

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
