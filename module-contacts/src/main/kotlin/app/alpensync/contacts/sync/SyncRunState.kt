// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

/** Phases of one contacts sync run (per account). */
enum class SyncRunPhase {
    IDLE,
    LISTING,
    DIFFING,
    GUARD_CHECK,
    APPLYING,
    SWEEPING,
    COMPLETED,
    ABORTED,
    FAILED,
}

/**
 * Explicit state machine for a sync run — transitions are a fixed table,
 * anything off-table throws. Terminal phases (COMPLETED / ABORTED / FAILED)
 * are final: a new run gets a new tracker. The mass-delete guard is its own
 * phase so an abort is always preceded by DIFFING and never touches APPLYING.
 */
class SyncRunTracker {

    var phase: SyncRunPhase = SyncRunPhase.IDLE
        private set

    fun transition(to: SyncRunPhase) {
        require(to in TRANSITIONS.getValue(phase)) {
            "Illegal sync-run transition: $phase -> $to"
        }
        phase = to
    }

    fun isTerminal(): Boolean = TRANSITIONS.getValue(phase).isEmpty()

    companion object {
        private val TRANSITIONS: Map<SyncRunPhase, Set<SyncRunPhase>> = mapOf(
            SyncRunPhase.IDLE to setOf(SyncRunPhase.LISTING),
            SyncRunPhase.LISTING to setOf(SyncRunPhase.DIFFING, SyncRunPhase.FAILED),
            SyncRunPhase.DIFFING to setOf(SyncRunPhase.GUARD_CHECK, SyncRunPhase.FAILED),
            SyncRunPhase.GUARD_CHECK to setOf(SyncRunPhase.APPLYING, SyncRunPhase.ABORTED),
            SyncRunPhase.APPLYING to setOf(SyncRunPhase.SWEEPING, SyncRunPhase.FAILED),
            SyncRunPhase.SWEEPING to setOf(SyncRunPhase.COMPLETED, SyncRunPhase.FAILED),
            SyncRunPhase.COMPLETED to emptySet(),
            SyncRunPhase.ABORTED to emptySet(),
            SyncRunPhase.FAILED to emptySet(),
        )
    }
}
