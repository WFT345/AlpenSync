// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live counts for one contacts sync run. [listed] is 0 until the metadata
 * walk finishes; [processed] counts every listed id handled (skip, fetch,
 * or per-contact error). [applied] is inserts + updates written so far.
 */
data class SyncProgress(
    val listed: Int = 0,
    val processed: Int = 0,
    val applied: Int = 0,
    val inFlight: Boolean = false,
) {
    /** Null while the listing is still running, otherwise 0f..1f. */
    val fraction: Float?
        get() = if (listed > 0) (processed.toFloat() / listed.toFloat()).coerceIn(0f, 1f) else null
}

/**
 * Process-wide last-progress holder so the home screen can watch a
 * SyncAdapter run (same process, different thread) the same way it watches
 * an in-app Sync now. The engine itself takes a callback; production wiring
 * publishes here.
 */
object SyncProgressHub {
    @Volatile
    var latest: SyncProgress = SyncProgress()
        private set

    private val listeners = CopyOnWriteArrayList<(SyncProgress) -> Unit>()

    fun publish(progress: SyncProgress) {
        latest = progress
        for (listener in listeners) listener(progress)
    }

    fun addListener(listener: (SyncProgress) -> Unit) {
        listeners.add(listener)
        listener(latest)
    }

    fun removeListener(listener: (SyncProgress) -> Unit) {
        listeners.remove(listener)
    }

    fun reset() {
        publish(SyncProgress())
    }
}
