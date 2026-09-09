package app.alpensync.ui

import app.alpensync.SyncErrorKind
import app.alpensync.contacts.sync.SyncProgress
import app.alpensync.contacts.sync.SyncReport

enum class HomeHeadline {
    NEEDS_ACCESS,
    CANT_START,
    SYNCING,
    COULDNT_SYNC,
    NEEDS_RELINK,
    HELD_BACK,
    READY,
    IN_SYNC,
}

data class HomeRunSummary(
    val listed: Int,
    val inserted: Int,
    val updated: Int,
    val contactErrors: Int,
    val cardFailures: Int,
)

enum class StatusTone { NONE, OK, BUSY, ATTENTION, PROBLEM }

data class HomeStatus(
    val headline: HomeHeadline,
    val run: HomeRunSummary? = null,
    val progress: SyncProgress? = null,
) {
    val tone: StatusTone
        get() = when (headline) {
            HomeHeadline.READY -> StatusTone.NONE
            HomeHeadline.IN_SYNC -> StatusTone.OK
            HomeHeadline.SYNCING -> StatusTone.BUSY
            HomeHeadline.NEEDS_ACCESS -> StatusTone.ATTENTION
            HomeHeadline.CANT_START,
            HomeHeadline.COULDNT_SYNC,
            HomeHeadline.NEEDS_RELINK,
            HomeHeadline.HELD_BACK,
            -> StatusTone.PROBLEM
        }
}

fun deriveHomeStatus(
    permissionGranted: Boolean,
    accountReady: Boolean,
    syncing: Boolean,
    lastError: SyncErrorKind?,
    lastReport: SyncReport?,
    progress: SyncProgress? = null,
): HomeStatus {
    if (!permissionGranted) return HomeStatus(HomeHeadline.NEEDS_ACCESS)
    if (!accountReady) return HomeStatus(HomeHeadline.CANT_START)
    // takeIf: the hub keeps the LAST run's final snapshot — showing it at the
    // start of the next run would flash a full "N of N" bar before the engine
    // publishes its reset. Only live snapshots reach the progress line.
    if (syncing) return HomeStatus(HomeHeadline.SYNCING, progress = progress?.takeIf { it.inFlight })
    if (lastError == SyncErrorKind.NO_SESSION) return HomeStatus(HomeHeadline.NEEDS_RELINK)
    if (lastError != null) return HomeStatus(HomeHeadline.COULDNT_SYNC)
    val report = lastReport ?: return HomeStatus(HomeHeadline.READY)
    if (report.guardAbort != null) return HomeStatus(HomeHeadline.HELD_BACK)
    return HomeStatus(HomeHeadline.IN_SYNC, summaryOf(report))
}

private fun summaryOf(report: SyncReport): HomeRunSummary = HomeRunSummary(
    listed = report.listed,
    inserted = report.inserted,
    updated = report.updated,
    contactErrors = report.contactErrors,
    cardFailures = report.cardFailures,
)
