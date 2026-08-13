package app.alpensync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.alpensync.AccountSnapshot
import app.alpensync.LoginController
import app.alpensync.LoginUiState
import app.alpensync.R
import app.alpensync.SyncDebugController
import app.alpensync.contacts.sync.SyncScheduler
import app.alpensync.ui.theme.AlpenBg
import kotlinx.coroutines.launch

@Composable
internal fun HomePane(
    state: LoginUiState.LoggedIn,
    controller: LoginController,
    syncController: SyncDebugController,
    onLogout: () -> Unit,
    onShowLegal: (LegalKind) -> Unit,
) {
    if (state.snapshot == null) {
        LaunchedEffect(state) { controller.loadAccount() }
    }
    HomeReady(
        accountLabel = state.username ?: state.snapshot?.let(::firstAddress),
        syncController = syncController,
        onLogout = onLogout,
        onShowLegal = onShowLegal,
        onRelink = controller::promptRelink,
    )
}

@Composable
private fun HomeReady(
    accountLabel: String?,
    syncController: SyncDebugController,
    onLogout: () -> Unit,
    onShowLegal: (LegalKind) -> Unit,
    onRelink: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        syncController.refreshPermissionState()
        syncController.ensureAccountAndSchedule()
    }
    LaunchedEffect(Unit) {
        syncController.refreshPermissionState()
        syncController.ensureAccountAndSchedule()
    }
    val status = homeStatusOf(syncController)
    Column(modifier = Modifier.fillMaxSize()) {
        HomeIntro(status)
        AlpenVSpace(14)
        PhoneCard(accountLabel, status, syncController)
        if (hasCallout(status)) {
            AlpenVSpace(10)
            ProblemCard(status, syncController)
        }
        HowItWorksToggle()
        Spacer(Modifier.weight(1f, fill = true))
        HomePrimary(status.headline, syncController, onRelink) {
            permissionLauncher.launch(SyncDebugController.CONTACTS_PERMISSIONS)
        }
        AlpenTextLink(label = stringResource(R.string.logout_button), onClick = onLogout)
        LegalLinks(onShowLegal)
    }
}

@Composable
internal fun HomeTopBar(status: HomeStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlpenWordmark()
        badgeLabel(status)?.let { AlpenBadge(it, status.tone) }
    }
}

@Composable
private fun PhoneCard(
    accountLabel: String?,
    status: HomeStatus,
    syncController: SyncDebugController,
) {
    AlpenCard(Modifier.fillMaxWidth()) {
        AlpenLockup(stringResource(R.string.home_phone_title))
        if (!accountLabel.isNullOrBlank()) {
            AlpenQuietRow(stringResource(R.string.home_account_label), accountLabel)
        }
        status.run?.let {
            if (!accountLabel.isNullOrBlank()) AlpenRuleLine()
            AlpenQuietRow(stringResource(R.string.home_last_run_label), runLockupText(it))
        }
        if (syncController.contactsPermissionGranted && syncController.syncAccountReady) {
            if (!accountLabel.isNullOrBlank() || status.run != null) AlpenRuleLine()
            AlpenLockup(stringResource(R.string.sync_interval_label))
            IntervalRow(syncController)
        }
    }
}

@Composable
private fun ProblemCard(status: HomeStatus, syncController: SyncDebugController) {
    val body = calloutBody(status, syncController) ?: return
    AlpenCard(Modifier.fillMaxWidth()) {
        AlpenLockup(badgeLabel(status) ?: stringResource(R.string.home_needs_access))
        AlpenBody(body)
    }
}

@Composable
private fun calloutBody(status: HomeStatus, syncController: SyncDebugController): String? =
    when (status.headline) {
        HomeHeadline.NEEDS_ACCESS -> stringResource(R.string.home_needs_access_body)
        HomeHeadline.CANT_START -> stringResource(R.string.home_cant_start_lockup)
        HomeHeadline.HELD_BACK -> stringResource(R.string.home_held_back_lockup)
        HomeHeadline.NEEDS_RELINK -> stringResource(R.string.session_expired_notice)
        HomeHeadline.COULDNT_SYNC -> syncController.lastError?.let { syncErrorText(it) }
        else -> null
    }

private fun hasCallout(status: HomeStatus): Boolean = when (status.headline) {
    HomeHeadline.NEEDS_ACCESS,
    HomeHeadline.CANT_START,
    HomeHeadline.HELD_BACK,
    HomeHeadline.NEEDS_RELINK,
    HomeHeadline.COULDNT_SYNC,
    -> true
    else -> false
}

@Composable
private fun HomePrimary(
    headline: HomeHeadline,
    controller: SyncDebugController,
    onRelink: () -> Unit,
    onGrant: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    when (headline) {
        HomeHeadline.NEEDS_ACCESS -> AlpenPrimaryButton(
            label = stringResource(R.string.sync_permission_grant),
            onClick = onGrant,
        )
        HomeHeadline.CANT_START -> { }
        HomeHeadline.NEEDS_RELINK -> AlpenPrimaryButton(
            label = stringResource(R.string.relink_button),
            onClick = onRelink,
        )
        else -> AlpenPrimaryButton(
            label = stringResource(R.string.sync_now_button),
            enabled = !controller.syncing,
            busy = controller.syncing,
            onClick = { scope.launch { controller.syncNow() } },
        )
    }
}

@Composable
private fun IntervalRow(controller: SyncDebugController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AlpenPill)
            .background(AlpenBg)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SyncScheduler.ALLOWED_PERIODS_MINUTES.forEach { minutes ->
            AlpenChip(
                label = intervalLabel(minutes),
                selected = minutes == controller.periodMinutes,
                onClick = { controller.selectPeriod(minutes) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun badgeLabel(status: HomeStatus): String? = when (status.headline) {
    HomeHeadline.NEEDS_ACCESS -> stringResource(R.string.home_badge_access)
    HomeHeadline.CANT_START -> stringResource(R.string.home_badge_cant_start)
    HomeHeadline.SYNCING -> stringResource(R.string.home_badge_syncing)
    HomeHeadline.COULDNT_SYNC -> stringResource(R.string.home_badge_error)
    HomeHeadline.HELD_BACK -> stringResource(R.string.home_badge_held)
    HomeHeadline.NEEDS_RELINK -> stringResource(R.string.home_badge_relink)
    HomeHeadline.IN_SYNC -> stringResource(R.string.home_badge_in_sync)
    HomeHeadline.READY -> null
}

@Composable
private fun runLockupText(run: HomeRunSummary): String {
    val parts = mutableListOf<String>()
    parts += if (run.listed == 0) {
        stringResource(R.string.home_lockup_empty)
    } else {
        stringResource(R.string.home_lockup_contacts, run.listed)
    }
    if (run.inserted > 0) parts += stringResource(R.string.home_lockup_new, run.inserted)
    if (run.updated > 0) parts += stringResource(R.string.home_lockup_changed, run.updated)
    if (run.contactErrors > 0 || run.cardFailures > 0) {
        parts += stringResource(R.string.home_lockup_errors)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun intervalLabel(minutes: Long): String =
    if (minutes == SyncScheduler.DAILY_MINUTES) {
        stringResource(R.string.sync_interval_daily)
    } else {
        stringResource(R.string.sync_interval_minutes, minutes)
    }

private fun homeStatusOf(controller: SyncDebugController): HomeStatus = deriveHomeStatus(
    permissionGranted = controller.contactsPermissionGranted,
    accountReady = controller.syncAccountReady,
    syncing = controller.syncing,
    lastError = controller.lastError,
    lastReport = controller.lastReport,
)

private fun firstAddress(snapshot: AccountSnapshot): String? = snapshot.addresses.firstOrNull()?.email
