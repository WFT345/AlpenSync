package app.alpensync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.alpensync.contacts.sync.SyncReport
import app.alpensync.contacts.sync.SyncScheduler
import kotlinx.coroutines.launch

/**
 * The M2d sync section of the logged-in debug screen: contacts-permission
 * rationale + grant flow, sync-account status, the 15/30/60-minute interval
 * choice (plan §3.3), and the "Sync now" button with the run's real counts.
 * Lives in its own file because DebugLoginScreen.kt sits at detekt's
 * 300-line file limit.
 */
@Composable
fun SyncDebugSection(controller: SyncDebugController) {
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        controller.refreshPermissionState()
        controller.ensureAccountAndSchedule()
    }

    LaunchedEffect(Unit) {
        controller.refreshPermissionState()
        controller.ensureAccountAndSchedule()
    }

    Text(text = stringResource(R.string.sync_header), style = MaterialTheme.typography.titleSmall)
    if (!controller.contactsPermissionGranted) {
        Text(text = stringResource(R.string.sync_permission_rationale), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { permissionLauncher.launch(SyncDebugController.CONTACTS_PERMISSIONS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sync_permission_grant))
        }
        return
    }
    if (!controller.syncAccountReady) {
        Text(text = stringResource(R.string.sync_account_missing), style = MaterialTheme.typography.bodyMedium)
        return
    }
    IntervalRow(controller)
    Button(
        onClick = { scope.launch { controller.syncNow() } },
        enabled = !controller.syncing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (controller.syncing) R.string.sync_running else R.string.sync_now_button))
    }
    controller.lastError?.let { Text(text = syncErrorText(it), color = MaterialTheme.colorScheme.error) }
    controller.lastReport?.let { SyncReportBlock(it) }
}

/** "1440 min" is not a period anyone reads as a day. */
@Composable
private fun intervalLabel(minutes: Long): String =
    if (minutes == SyncScheduler.DAILY_MINUTES) {
        stringResource(R.string.sync_interval_daily)
    } else {
        stringResource(R.string.sync_interval_minutes, minutes)
    }

@Composable
private fun IntervalRow(controller: SyncDebugController) {
    Text(text = stringResource(R.string.sync_interval_label), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncScheduler.ALLOWED_PERIODS_MINUTES.forEach { minutes ->
            OutlinedButton(
                onClick = { controller.selectPeriod(minutes) },
                enabled = minutes != controller.periodMinutes,
            ) {
                Text(intervalLabel(minutes))
            }
        }
    }
}

@Composable
private fun SyncReportBlock(report: SyncReport) {
    Text(
        text = stringResource(
            R.string.sync_report_counts,
            report.listed, report.inserted, report.updated, report.unchanged,
            report.tombstonedNow, report.tombstonedPending, report.swept, report.contactErrors,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(
            R.string.sync_report_detail,
            report.fetched, report.skippedNotSyncable, report.cardFailures,
            report.unverifiedContacts, report.restored,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    report.guardAbort?.let {
        Text(
            text = stringResource(R.string.sync_report_guard_abort, it.pendingDeletions, it.lastKnownTotal),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun syncErrorText(kind: SyncErrorKind): String = stringResource(
    when (kind) {
        SyncErrorKind.NO_SESSION -> R.string.sync_error_no_session
        SyncErrorKind.HUMAN_VERIFICATION -> R.string.sync_error_human_verification
        SyncErrorKind.APP_VERSION_REJECTED -> R.string.sync_error_appversion
        SyncErrorKind.KEY_UNLOCK -> R.string.sync_error_key_unlock
        SyncErrorKind.NETWORK -> R.string.sync_error_network
        SyncErrorKind.UNEXPECTED -> R.string.sync_error_unexpected
    },
)
