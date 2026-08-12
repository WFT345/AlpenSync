// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// The guard pcontacts documents but never wires (research notes Section 4.3
// warning; ADR 0005 Section 1 makes us wire it).

package app.alpensync.contacts.sync

/**
 * Mass-delete guard (ADR 0005 open question 2, approved 50%/10): if a sync
 * run would delete more than half of the contacts we knew about, the run
 * aborts BEFORE any delete is applied — an API fault returning an empty or
 * truncated listing must not wipe the phone. The guard is inactive below
 * [FLOOR_KNOWN_TOTAL] known contacts (small accounts legitimately churn
 * past 50%).
 *
 * Pure function over the diff + sync_state.last_known_total; the engine
 * surfaces an [Verdict.Abort] as a typed error in the sync log.
 */
object MassDeleteGuard {

    const val FLOOR_KNOWN_TOTAL = 10

    sealed interface Verdict {
        data object Proceed : Verdict

        data class Abort(val pendingDeletions: Int, val lastKnownTotal: Int) : Verdict
    }

    fun check(diff: ContactDiff, lastKnownTotal: Int): Verdict =
        check(diff.pendingDeletionCount, lastKnownTotal)

    /**
     * Abort iff the account is past the floor AND pending deletions EXCEED
     * 50% of [lastKnownTotal] — exactly half still proceeds (`* 2 >` keeps
     * integer math, no floats).
     */
    fun check(pendingDeletions: Int, lastKnownTotal: Int): Verdict =
        if (lastKnownTotal >= FLOOR_KNOWN_TOTAL && pendingDeletions * 2 > lastKnownTotal) {
            Verdict.Abort(pendingDeletions, lastKnownTotal)
        } else {
            Verdict.Proceed
        }
}
