// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path app/.../sync/SyncScheduler.kt. Deviations: the period is minutes
// (plan §3.3's user-configurable choice — WorkManager's own floor is 15 min)
// with a plain-prefs store for the debug UI; pcontacts' fixed 12 h default
// becomes a DAILY default (owner decision, 2026-08-12).
//
// What this period does and does not cover: it paces only the PULL of
// changes made elsewhere (Proton web, another device). Proton has no push
// for third-party clients and this app ships no Play Services, so remote
// changes can only be found by asking — cheaply, via the event stream's
// last-seen-ID (:core:events, M3). Local edits do NOT wait for it:
// ContactsProvider flags the raw contact dirty and the OS wakes the sync
// adapter itself once M3 sets supportsUploading="true" (ADR 0007).

package app.alpensync.contacts.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules/cancels [PeriodicSyncWorker] and stores the sync interval. The
 * interval choice is a plain (non-secret) preference; KEEP policy makes
 * [schedulePeriodic] idempotent, so callers invoke it after every login
 * without clobbering a user's chosen interval.
 */
object SyncScheduler {

    /** One day, the default period. */
    const val DAILY_MINUTES: Long = 24L * 60L

    /**
     * Offered periods. Plan §3.3 listed 15/30/60; 15 is dropped (owner
     * decision, 2026-08-12) — it was never honest. WorkManager's floor IS
     * 15 min, but the OS batches periodic work and Doze stretches it, so a
     * 15-minute promise the platform will not keep is worse than not
     * offering it. A stored 15 falls back to the default via
     * [storedPeriodMinutes]'s allow-list check.
     */
    val ALLOWED_PERIODS_MINUTES: List<Long> = listOf(30L, 60L, DAILY_MINUTES)

    /**
     * Daily (owner decision, 2026-08-12). The period governs only how fast
     * web-side edits reach the phone, and "Sync now" covers impatience —
     * cheap beats eager for a background poll on a battery.
     */
    const val DEFAULT_PERIOD_MINUTES: Long = DAILY_MINUTES

    fun schedulePeriodic(context: Context) {
        enqueue(context, storedPeriodMinutes(context), ExistingPeriodicWorkPolicy.KEEP)
    }

    fun storedPeriodMinutes(context: Context): Long =
        prefs(context).getLong(KEY_PERIOD_MINUTES, DEFAULT_PERIOD_MINUTES)
            .takeIf { it in ALLOWED_PERIODS_MINUTES } ?: DEFAULT_PERIOD_MINUTES

    /** Persists the choice and reschedules (UPDATE picks up the new period). */
    fun setPeriodMinutes(context: Context, minutes: Long) {
        require(minutes in ALLOWED_PERIODS_MINUTES) { "period must be one of $ALLOWED_PERIODS_MINUTES" }
        prefs(context).edit().putLong(KEY_PERIOD_MINUTES, minutes).apply()
        enqueue(context, minutes, ExistingPeriodicWorkPolicy.UPDATE)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PeriodicSyncWorker.UNIQUE_NAME)
    }

    private fun enqueue(context: Context, periodMinutes: Long, policy: ExistingPeriodicWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(periodMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PeriodicSyncWorker.UNIQUE_NAME,
            policy,
            request,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private const val PREFS_FILE = "alpensync_sync"
    private const val KEY_PERIOD_MINUTES = "period_minutes"
}
