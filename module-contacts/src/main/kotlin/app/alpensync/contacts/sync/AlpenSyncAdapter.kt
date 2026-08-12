// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path app/.../sync/ProtonSyncAdapter.kt. Deviations: read-only M2 pipeline
// (no write engine); no user-facing notifier at M2d (the debug screen shows
// the report; M4 grows the sync-log viewer); SafeLog instead of their Logger.

package app.alpensync.contacts.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import app.alpensync.contacts.account.ContactsAccountSettings
import app.alpensync.core.api.log.SafeLog
import kotlinx.coroutines.runBlocking

/**
 * The single place sync work runs (ADR 0005 Section 5): the WorkManager
 * poker and the system sync framework both end up here. `onPerformSync`
 * bridges to coroutines with runBlocking — pcontacts' pattern, chosen over
 * DAVx⁵'s adapter-enqueues-a-worker inversion because M2's runs are short
 * and idempotent and the framework's SyncResult stats stay meaningful.
 *
 * Error taxonomy → SyncResult: re-auth / key-unlock / 9001 / app-version
 * rejection → numAuthExceptions (the framework stops retrying); transport
 * and provider failures → numIoExceptions.
 */
class AlpenSyncAdapter(
    context: Context,
    autoInitialize: Boolean = true,
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    // Scoped exception to detekt's TooGenericExceptionCaught (plan Rule 16
    // requires the why): this frame is a process boundary, not ordinary code.
    // Anything escaping onPerformSync is a FATAL EXCEPTION on the framework's
    // sync thread and kills the app — the failure mode this catch exists to
    // prevent, and one that already happened (InterruptedException, the
    // post-login initial sync, 2026-08-12). Narrow catches cannot give that
    // guarantee, because the guarantee is precisely about the unlisted case.
    // SyncFailure.classify keeps the taxonomy specific and testable; only the
    // final arm is broad. Same precedent as :app's containUnexpected.
    @Suppress("TooGenericExceptionCaught")
    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        // Non-fatal by design: makes our contacts visible in the Contacts
        // app; self-heals on the next run when an OEM provider rejects it.
        ContactsAccountSettings.ensureVisibleAndSyncable(context.contentResolver, account)
        try {
            val report = runBlocking {
                ContactsSyncBootstrap.createEngine(context, provider, account)?.run()
            } ?: return // no session / no key material — SafeLog'd in the bootstrap
            syncResult.stats.numInserts += report.inserted.toLong()
            syncResult.stats.numUpdates += report.updated.toLong()
            syncResult.stats.numDeletes += report.swept.toLong()
        } catch (t: Throwable) {
            record(SyncFailure.classify(t), syncResult)
        }
    }

    /**
     * The error TAXONOMY (ADR 0005 Section 5) applied to the SyncResult: the
     * stat is the record — an exception body never reaches a log (Rule 1).
     *
     * The catch above is deliberately total. An escape from `onPerformSync`
     * is a FATAL EXCEPTION on the framework's sync thread, i.e. the whole
     * process dies; a sync that cannot complete must fail closed and be
     * retried, never crash the app (plan Rules 5/19). This is the sync-side
     * twin of `:app`'s containUnexpected.
     */
    private fun record(failure: SyncFailure, syncResult: SyncResult) {
        when (failure) {
            SyncFailure.AUTH -> syncResult.stats.numAuthExceptions += 1
            SyncFailure.IO -> syncResult.stats.numIoExceptions += 1
            SyncFailure.CANCELLED -> {
                // Restore what runBlocking consumed, so anything further up
                // still sees this thread as cancelled.
                Thread.currentThread().interrupt()
                SafeLog.log(SafeLog.Event.SYNC_CANCELLED)
                syncResult.stats.numIoExceptions += 1
            }
            SyncFailure.UNEXPECTED -> {
                SafeLog.log(SafeLog.Event.SYNC_UNEXPECTED_ERROR)
                syncResult.stats.numIoExceptions += 1
            }
        }
    }
}
