// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path app/.../sync/PeriodicSyncWorker.kt

package app.alpensync.contacts.sync

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.alpensync.contacts.account.ACCOUNT_TYPE
import app.alpensync.contacts.account.CONTACTS_AUTHORITY

/**
 * Periodic belt-and-suspenders for the SyncAdapter (plan §3.3): OEM power
 * management can mute sync-framework scheduling, so WorkManager fires under
 * conservative constraints and pokes [ContentResolver.requestSync]. The
 * actual sync work runs in [AlpenSyncAdapter.onPerformSync] — this worker
 * only kicks the framework, so a killed sync retries via the framework.
 */
class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        RelinkNotifier.repostIfNeeded(applicationContext)
        val accounts = AccountManager.get(applicationContext).getAccountsByType(ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            // Logged out → nothing to poke. Success: the next periodic firing
            // re-checks; retrying an empty account list would be pointless.
            return Result.success()
        }
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        accounts.forEach { ContentResolver.requestSync(it, CONTACTS_AUTHORITY, extras) }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "app.alpensync.periodic-sync"
    }
}
