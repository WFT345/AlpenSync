package app.alpensync

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.alpensync.hv.HumanVerificationSheet
import kotlinx.coroutines.launch

/**
 * The M1 debug login screen (plan Section 6 acceptance). Plain Material3,
 * functional over pretty — the real design phase comes at M4. All state
 * transitions go through [LoginController]; this file only renders. Shared
 * blocks live in DebugLoginParts.kt and the M2d sync section in
 * DebugSyncSection.kt (this file sits at detekt's 300-line limit).
 */
@Composable
fun DebugLoginScreen(controller: LoginController, syncController: SyncDebugController) {
    val scope = rememberCoroutineScope()
    // Hoisted above the state branches so a failed login keeps the username.
    var username by rememberSaveable { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DebugHeader()
            LoginStateContent(
                state = controller.state,
                username = username,
                onUsernameChange = { username = it },
                controller = controller,
                syncController = syncController,
                scope = scope,
            )
        }
    }
}

@Composable
private fun LoginStateContent(
    state: LoginUiState,
    username: String,
    onUsernameChange: (String) -> Unit,
    controller: LoginController,
    syncController: SyncDebugController,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when (state) {
        is LoginUiState.LoggedOut -> LoginForm(
            notice = state.notice,
            username = username,
            onUsernameChange = onUsernameChange,
            onLogin = { name, password -> scope.launch { controller.login(name, password) } },
        )
        LoginUiState.LoggingIn -> ProgressBlock(stringResource(R.string.logging_in))
        LoginUiState.NeedsTotp -> TotpForm(
            onSubmit = { code -> scope.launch { controller.submitTwoFactorCode(code) } },
        )
        is LoginUiState.HumanVerification -> HumanVerificationSheet(
            startToken = state.startToken,
            methods = state.methods,
            onSuccess = { token, tokenType ->
                scope.launch { controller.completeHumanVerification(token, tokenType) }
            },
            onCancel = controller::onHumanVerificationCancelled,
        )
        is LoginUiState.LoggedIn -> LoggedInBlock(
            state = state,
            controller = controller,
            syncController = syncController,
            onLogout = { scope.launch { controller.logout() } },
        )
        is LoginUiState.Error -> ErrorBlock(
            error = state,
            onRetry = controller::retryAccountLoad,
            onBack = controller::backToLogin,
            onLogout = { scope.launch { controller.logout() } },
        )
    }
}

@Composable
private fun DebugHeader() {
    Text(
        text = stringResource(R.string.debug_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.debug_subtitle),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LoginForm(
    notice: LogoutNotice?,
    username: String,
    onUsernameChange: (String) -> Unit,
    onLogin: (String, CharArray) -> Unit,
) {
    // TextFieldState keeps the password out of Strings and instance-state
    // Bundles; the field is cleared the moment the CharArray is handed off.
    val password = rememberTextFieldState()

    if (notice != null) {
        Text(text = noticeText(notice), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.username_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    // No Material secure field exists in the pinned material3 (1.3.x), so the
    // foundation secure field gets a plain outline — debug screen, not final UX.
    Text(text = stringResource(R.string.password_label), style = MaterialTheme.typography.labelMedium)
    BasicSecureTextField(
        state = password,
        textObfuscationMode = TextObfuscationMode.RevealLastTyped,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
    Button(
        onClick = {
            val chars = CharArray(password.text.length) { password.text[it] }
            password.clearText()
            onLogin(username.trim(), chars)
        },
        enabled = username.isNotBlank() && password.text.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.login_button))
    }
}

@Composable
private fun TotpForm(onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }

    Text(text = stringResource(R.string.totp_prompt), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = code,
        onValueChange = { input -> code = input.filter { it in '0'..'9' }.take(TOTP_CODE_LENGTH) },
        label = { Text(stringResource(R.string.totp_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            val normalized = normalizeTotpCode(code) ?: return@Button
            code = ""
            onSubmit(normalized)
        },
        enabled = normalizeTotpCode(code) != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.totp_submit))
    }
}

@Composable
private fun LoggedInBlock(
    state: LoginUiState.LoggedIn,
    controller: LoginController,
    syncController: SyncDebugController,
    onLogout: () -> Unit,
) {
    val snapshot = state.snapshot
    if (snapshot == null) {
        LaunchedEffect(state) { controller.loadAccount() }
        ProgressBlock(stringResource(R.string.account_loading))
        return
    }
    Text(text = stringResource(R.string.login_success), style = MaterialTheme.typography.titleMedium)
    if (state.restoredSession) {
        Text(text = stringResource(R.string.restored_session_note), style = MaterialTheme.typography.bodySmall)
    }
    state.username?.let {
        Text(text = stringResource(R.string.logged_in_as, it), style = MaterialTheme.typography.bodyMedium)
    }
    Text(text = stringResource(R.string.addresses_header), style = MaterialTheme.typography.titleSmall)
    snapshot.addresses.forEach { AddressRow(it) }
    Text(
        text = stringResource(R.string.decryption_keys_count, snapshot.decryptionKeyCount),
        style = MaterialTheme.typography.bodySmall,
    )
    TokenProofBlock(snapshot.tokenProof)
    SyncDebugSection(syncController)
    Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.logout_button))
    }
}

@Composable
private fun ErrorBlock(
    error: LoginUiState.Error,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    Text(
        text = stringResource(R.string.error_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Text(text = errorMessage(error), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error.fromAccountLoad) {
            Button(onClick = onRetry) { Text(stringResource(R.string.retry_button)) }
            OutlinedButton(onClick = onLogout) { Text(stringResource(R.string.logout_button)) }
        } else {
            Button(onClick = onBack) { Text(stringResource(R.string.back_button)) }
        }
    }
}
