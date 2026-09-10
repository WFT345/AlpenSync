// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// The guard pcontacts documents but never wires (research notes Section 4.3
// warning; ADR 0005 Section 1 makes us wire it).

package app.alpensync.contacts.sync

/**
 * Mass-delete guard (ADR 0005 open question 2, approved 50%/10): if a sync
 * run would delete more than half of the contacts it could delete, the run
 * aborts BEFORE any delete is applied — an API fault returning an empty or
 * truncated listing must not wipe the phone. The guard is inactive below
 * [FLOOR_DELETABLE_TOTAL] deletable contacts (small accounts legitimately
 * churn past 50%).
 *
 * The denominator is the deletable universe — the run-start snapshot of
 * syncable mappings — not a persisted watermark: a first pull killed midway
 * has exactly the contacts it landed as its universe, so a later empty
 * listing still trips the guard (ADR 0005 §1 amendment). Grace-period
 * tombstones are NOT added on top: their mapping rows stay until the sweep,
 * so they are already in the mapping count (adding them would double-count).
 *
 * Pure function over the diff + that count; the engine surfaces an
 * [Verdict.Abort] as a typed error in the sync log.
 */
object MassDeleteGuard {

    const val FLOOR_DELETABLE_TOTAL = 10

    sealed interface Verdict {
        data object Proceed : Verdict

        data class Abort(val pendingDeletions: Int, val deletableTotal: Int) : Verdict
    }

    fun check(diff: ContactDiff, deletableTotal: Int): Verdict =
        check(diff.pendingDeletionCount, deletableTotal)

    /**
     * Abort iff the deletable set is past the floor AND pending deletions
     * EXCEED 50% of it — exactly half still proceeds (`* 2 >` keeps integer
     * math, no floats).
     */
    fun check(pendingDeletions: Int, deletableTotal: Int): Verdict =
        if (deletableTotal >= FLOOR_DELETABLE_TOTAL && pendingDeletions * 2 > deletableTotal) {
            Verdict.Abort(pendingDeletions, deletableTotal)
        } else {
            Verdict.Proceed
        }
}
