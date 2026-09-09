package app.alpensync.ui

import app.alpensync.SyncErrorKind
import app.alpensync.contacts.sync.GuardAbort
import app.alpensync.contacts.sync.SyncProgress
import app.alpensync.contacts.sync.SyncReport
import app.alpensync.contacts.sync.SyncRunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeStatusTest {

    @Test
    fun permission_denied_wins_over_everything_else() {
        val status = deriveHomeStatus(
            permissionGranted = false,
            accountReady = true,
            syncing = true,
            lastError = SyncErrorKind.NETWORK,
            lastReport = report(),
        )
        assertEquals(HomeHeadline.NEEDS_ACCESS, status.headline)
        assertNull(status.run)
    }

    @Test
    fun missing_account_is_cant_start() {
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = false,
            syncing = false,
            lastError = null,
            lastReport = null,
        )
        assertEquals(HomeHeadline.CANT_START, status.headline)
    }

    @Test
    fun in_flight_run_is_syncing() {
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = true,
            lastError = null,
            lastReport = report(),
        )
        assertEquals(HomeHeadline.SYNCING, status.headline)
    }

    @Test
    fun in_flight_run_carries_live_progress() {
        val progress = SyncProgress(listed = 907, processed = 312, applied = 312, inFlight = true)
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = true,
            lastError = null,
            lastReport = null,
            progress = progress,
        )
        assertEquals(HomeHeadline.SYNCING, status.headline)
        assertEquals(progress, status.progress)
        assertEquals(312f / 907f, status.progress?.fraction)
    }

    @Test
    fun a_finished_runs_lingering_snapshot_is_not_shown_as_live_progress() {
        // SyncProgressHub keeps the last run's final snapshot; at the start of
        // the next run (before the engine publishes its reset) the UI must not
        // flash that stale "900 of 900" as if it were the new run's progress.
        val stale = SyncProgress(listed = 900, processed = 900, applied = 900, inFlight = false)
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = true,
            lastError = null,
            lastReport = null,
            progress = stale,
        )
        assertEquals(HomeHeadline.SYNCING, status.headline)
        assertNull("stale final snapshot renders as indeterminate progress", status.progress)
    }

    @Test
    fun last_error_is_couldnt_sync() {
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = false,
            lastError = SyncErrorKind.NETWORK,
            lastReport = report(),
        )
        assertEquals(HomeHeadline.COULDNT_SYNC, status.headline)
    }

    @Test
    fun no_session_is_needs_relink() {
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = false,
            lastError = SyncErrorKind.NO_SESSION,
            lastReport = report(),
        )
        assertEquals(HomeHeadline.NEEDS_RELINK, status.headline)
        assertEquals(StatusTone.PROBLEM, status.tone)
    }

    @Test
    fun guard_abort_is_held_back() {
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = false,
            lastError = null,
            lastReport = report(guardAbort = GuardAbort(pendingDeletions = 40, deletableTotal = 50)),
        )
        assertEquals(HomeHeadline.HELD_BACK, status.headline)
    }

    @Test
    fun never_run_is_ready_with_no_badge() {
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = false,
            lastError = null,
            lastReport = null,
        )
        assertEquals(HomeHeadline.READY, status.headline)
        assertEquals(StatusTone.NONE, status.tone)
    }

    @Test
    fun finished_run_tones_ok() {
        assertEquals(StatusTone.OK, deriveHomeStatus(true, true, false, null, report()).tone)
    }

    @Test
    fun finished_run_is_in_sync_with_the_counts() {
        val status = deriveHomeStatus(
            permissionGranted = true,
            accountReady = true,
            syncing = false,
            lastError = null,
            lastReport = report(listed = 12, inserted = 1, updated = 2, contactErrors = 1),
        )
        assertEquals(HomeHeadline.IN_SYNC, status.headline)
        val expected = HomeRunSummary(
            listed = 12,
            inserted = 1,
            updated = 2,
            contactErrors = 1,
            cardFailures = 0,
        )
        assertEquals(expected, status.run)
    }

    private fun report(
        listed: Int = 0,
        inserted: Int = 0,
        updated: Int = 0,
        contactErrors: Int = 0,
        guardAbort: GuardAbort? = null,
    ): SyncReport = SyncReport(
        listed = listed,
        fetched = 0,
        inserted = inserted,
        updated = updated,
        unchanged = 0,
        tombstonedNow = 0,
        tombstonedPending = 0,
        swept = 0,
        restored = 0,
        skippedNotSyncable = 0,
        contactErrors = contactErrors,
        cardFailures = 0,
        unverifiedContacts = 0,
        guardAbort = guardAbort,
        phase = if (guardAbort == null) SyncRunPhase.COMPLETED else SyncRunPhase.ABORTED,
    )
}
