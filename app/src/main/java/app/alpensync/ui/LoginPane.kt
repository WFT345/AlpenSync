package app.alpensync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.alpensync.LoginUiState
import app.alpensync.LogoutNotice
import app.alpensync.R
import app.alpensync.TOTP_CODE_LENGTH
import app.alpensync.normalizeTotpCode

@Composable
internal fun LoginPane(
    notice: LogoutNotice?,
    username: String,
    onUsernameChange: (String) -> Unit,
    onLogin: (String, CharArray) -> Unit,
    busy: Boolean = false,
    onShowLegal: (LegalKind) -> Unit,
) {
    val password = rememberTextFieldState()
    val relink = notice != null
    AlpenPane {
        LoginHeader(relink, notice)
        AlpenVSpace(24)
        LoginFields(username, onUsernameChange, password, enabled = !busy)
        AlpenVSpace(20)
        AlpenPrimaryButton(
            label = stringResource(if (relink) R.string.relink_button else R.string.login_button),
            enabled = username.isNotBlank() && password.text.isNotEmpty(),
            busy = busy,
            onClick = {
                val chars = CharArray(password.text.length) { password.text[it] }
                password.clearText()
                onLogin(username.trim(), chars)
            },
        )
        Spacer(Modifier.weight(1f, fill = true))
        AlpenVSpace(24)
        AlpenLockup(stringResource(R.string.affiliation_lockup))
        LegalLinks(onShowLegal)
    }
}

@Composable
private fun LoginHeader(relink: Boolean, notice: LogoutNotice?) {
    AlpenScreenHead(
        hero = stringResource(if (relink) R.string.relink_hero else R.string.login_hero),
    )
    AlpenVSpace(10)
    AlpenBody(
        stringResource(if (relink) R.string.relink_lede else R.string.login_lede),
        mute = true,
    )
    if (notice != null) {
        AlpenVSpace(16)
        AlpenCard { AlpenBody(noticeText(notice)) }
    }
}

@Composable
private fun LoginFields(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: TextFieldState,
    enabled: Boolean,
) {
    AlpenLineField(
        value = username,
        onValueChange = onUsernameChange,
        label = stringResource(R.string.username_label),
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    AlpenVSpace(16)
    AlpenSecureField(
        state = password,
        label = stringResource(R.string.password_label),
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
    )
}

@Composable
internal fun TotpPane(onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlpenPane {
        AlpenScreenHead(
            hero = stringResource(R.string.totp_hero),
            lockup = stringResource(R.string.totp_lockup),
        )
        AlpenVSpace(28)
        AlpenLineField(
            value = code,
            onValueChange = { input -> code = input.filter { it in '0'..'9' }.take(TOTP_CODE_LENGTH) },
            label = stringResource(R.string.totp_label),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
        )
        AlpenVSpace(20)
        AlpenPrimaryButton(
            label = stringResource(R.string.totp_submit),
            enabled = normalizeTotpCode(code) != null,
            onClick = {
                val normalized = normalizeTotpCode(code) ?: return@AlpenPrimaryButton
                code = ""
                onSubmit(normalized)
            },
        )
    }
}

@Composable
internal fun ErrorPane(
    error: LoginUiState.Error,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AlpenScreenHead(stringResource(R.string.error_title))
        AlpenVSpace(16)
        AlpenCard {
            AlpenBody(errorMessage(error))
        }
        AlpenVSpace(20)
        if (error.fromAccountLoad) {
            AlpenPrimaryButton(label = stringResource(R.string.retry_button), onClick = onRetry)
            AlpenVSpace(8)
            AlpenTextLink(label = stringResource(R.string.logout_button), onClick = onLogout)
        } else {
            AlpenPrimaryButton(label = stringResource(R.string.back_button), onClick = onBack)
        }
    }
}
