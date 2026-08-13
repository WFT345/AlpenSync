package app.alpensync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.alpensync.contacts.sync.RelinkAlertPrefs
import app.alpensync.LoginController
import app.alpensync.LoginUiState
import app.alpensync.SyncDebugController
import app.alpensync.hv.HumanVerificationSheet
import app.alpensync.ui.theme.AlpenBg
import kotlinx.coroutines.launch

@Composable
fun AppScreen(controller: LoginController, syncController: SyncDebugController) {
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var splash by rememberSaveable { mutableStateOf(true) }
    var legalName by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(AlpenBg)) {
        WelcomeOrApp(
            controller = controller,
            syncController = syncController,
            username = username,
            onUsernameChange = { username = it },
            legalName = legalName,
            onLegal = { legalName = it },
            scope = scope,
        )
        if (splash) SplashPane(onFinished = { splash = false })
    }
}

@Composable
private fun WelcomeOrApp(
    controller: LoginController,
    syncController: SyncDebugController,
    username: String,
    onUsernameChange: (String) -> Unit,
    legalName: String?,
    onLegal: (String?) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val loggedOut = controller.state as? LoginUiState.LoggedOut
    var showWelcome by rememberSaveable {
        mutableStateOf(WelcomePrefs.shouldShow(context, loggedOut?.notice != null))
    }
    LaunchedEffect(controller.state) {
        if (controller.state is LoginUiState.LoggedIn) {
            WelcomePrefs(context).markSeen()
            showWelcome = false
        }
    }
    if (showWelcome && loggedOut != null) {
        WelcomePane(onContinue = {
            WelcomePrefs(context).markSeen()
            showWelcome = false
        })
        return
    }
    PaddedApp {
        if (legalName != null) {
            LegalPane(kind = LegalKind.valueOf(legalName), onBack = { onLegal(null) })
        } else {
            AppBody(
                controller = controller,
                syncController = syncController,
                username = username,
                onUsernameChange = onUsernameChange,
                scope = scope,
                onShowLegal = { kind -> onLegal(kind.name) },
            )
        }
    }
}

@Composable
private fun PaddedApp(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) { content() }
}

@Composable
private fun AppBody(
    controller: LoginController,
    syncController: SyncDebugController,
    username: String,
    onUsernameChange: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    onShowLegal: (LegalKind) -> Unit,
) {
    when (val state = controller.state) {
        is LoginUiState.LoggedOut -> LoginPane(
            notice = state.notice,
            username = username,
            onUsernameChange = onUsernameChange,
            onLogin = { name, password -> scope.launch { controller.login(name, password) } },
            onShowLegal = onShowLegal,
        )
        LoginUiState.LoggingIn -> LoginPane(
            notice = null,
            username = username,
            onUsernameChange = onUsernameChange,
            onLogin = { _, _ -> },
            busy = true,
            onShowLegal = onShowLegal,
        )
        LoginUiState.NeedsTotp -> TotpPane(
            onSubmit = { code -> scope.launch { controller.submitTwoFactorCode(code) } },
        )
        is LoginUiState.HumanVerification -> HvHost(
            state, controller, username, onUsernameChange, scope, onShowLegal,
        )
        is LoginUiState.LoggedIn -> LoggedInGate(
            state = state,
            controller = controller,
            syncController = syncController,
            onLogout = { scope.launch { controller.logout() } },
            onShowLegal = onShowLegal,
        )
        is LoginUiState.Error -> ErrorPane(
            error = state,
            onRetry = controller::retryAccountLoad,
            onBack = controller::backToLogin,
            onLogout = { scope.launch { controller.logout() } },
        )
    }
}

@Composable
private fun HvHost(
    state: LoginUiState.HumanVerification,
    controller: LoginController,
    username: String,
    onUsernameChange: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    onShowLegal: (LegalKind) -> Unit,
) {
    LoginPane(
        notice = null,
        username = username,
        onUsernameChange = onUsernameChange,
        onLogin = { _, _ -> },
        busy = true,
        onShowLegal = onShowLegal,
    )
    HumanVerificationSheet(
        startToken = state.startToken,
        methods = state.methods,
        onSuccess = { token, tokenType ->
            scope.launch { controller.completeHumanVerification(token, tokenType) }
        },
        onCancel = controller::onHumanVerificationCancelled,
    )
}

@Composable
private fun LoggedInGate(
    state: LoginUiState.LoggedIn,
    controller: LoginController,
    syncController: SyncDebugController,
    onLogout: () -> Unit,
    onShowLegal: (LegalKind) -> Unit,
) {
    val context = LocalContext.current
    var showPrimer by rememberSaveable {
        mutableStateOf(RelinkAlertPrefs.shouldShowPrimer(context))
    }
    if (showPrimer) {
        OnboardingAlertsPane(onFinished = { showPrimer = false })
    } else {
        HomePane(
            state = state,
            controller = controller,
            syncController = syncController,
            onLogout = onLogout,
            onShowLegal = onShowLegal,
        )
    }
}
