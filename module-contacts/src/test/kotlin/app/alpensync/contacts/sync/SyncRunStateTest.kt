// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sync-run state machine: the happy path, the guard-abort path, and enforcement. */
class SyncRunStateTest {

    @Test fun happyPathWalksEveryPhaseInOrder() {
        val run = SyncRunTracker()
        assertEquals(SyncRunPhase.IDLE, run.phase)
        run.transition(SyncRunPhase.LISTING)
        run.transition(SyncRunPhase.DIFFING)
        run.transition(SyncRunPhase.GUARD_CHECK)
        run.transition(SyncRunPhase.APPLYING)
        run.transition(SyncRunPhase.SWEEPING)
        run.transition(SyncRunPhase.COMPLETED)
        assertEquals(SyncRunPhase.COMPLETED, run.phase)
        assertTrue(run.isTerminal())
    }

    @Test fun guardAbortSkipsApplyingAndSweeping() {
        val run = SyncRunTracker()
        run.transition(SyncRunPhase.LISTING)
        run.transition(SyncRunPhase.DIFFING)
        run.transition(SyncRunPhase.GUARD_CHECK)
        run.transition(SyncRunPhase.ABORTED)
        assertTrue(run.isTerminal())
    }

    @Test fun offTableTransitionThrows() {
        val run = SyncRunTracker()
        assertThrows(IllegalArgumentException::class.java) {
            run.transition(SyncRunPhase.APPLYING)
        }
        assertEquals(SyncRunPhase.IDLE, run.phase)
    }

    @Test fun guardCheckCannotJumpStraightToCompleted() {
        val run = SyncRunTracker()
        run.transition(SyncRunPhase.LISTING)
        run.transition(SyncRunPhase.DIFFING)
        run.transition(SyncRunPhase.GUARD_CHECK)
        assertThrows(IllegalArgumentException::class.java) {
            run.transition(SyncRunPhase.COMPLETED)
        }
    }

    @Test fun terminalPhasesAreFinal() {
        val run = SyncRunTracker()
        run.transition(SyncRunPhase.LISTING)
        run.transition(SyncRunPhase.FAILED)
        assertTrue(run.isTerminal())
        assertThrows(IllegalArgumentException::class.java) {
            run.transition(SyncRunPhase.LISTING)
        }
    }

    @Test fun failureIsReachableFromEveryWorkingPhase() {
        for (phase in listOf(
            SyncRunPhase.LISTING,
            SyncRunPhase.DIFFING,
            SyncRunPhase.APPLYING,
            SyncRunPhase.SWEEPING,
        )) {
            val run = trackerIn(phase)
            run.transition(SyncRunPhase.FAILED)
            assertEquals(SyncRunPhase.FAILED, run.phase)
        }
    }

    private fun trackerIn(phase: SyncRunPhase): SyncRunTracker {
        val run = SyncRunTracker()
        when (phase) {
            SyncRunPhase.LISTING -> run.transition(SyncRunPhase.LISTING)
            SyncRunPhase.DIFFING -> {
                run.transition(SyncRunPhase.LISTING)
                run.transition(SyncRunPhase.DIFFING)
            }
            SyncRunPhase.APPLYING -> {
                run.transition(SyncRunPhase.LISTING)
                run.transition(SyncRunPhase.DIFFING)
                run.transition(SyncRunPhase.GUARD_CHECK)
                run.transition(SyncRunPhase.APPLYING)
            }
            SyncRunPhase.SWEEPING -> {
                run.transition(SyncRunPhase.LISTING)
                run.transition(SyncRunPhase.DIFFING)
                run.transition(SyncRunPhase.GUARD_CHECK)
                run.transition(SyncRunPhase.APPLYING)
                run.transition(SyncRunPhase.SWEEPING)
            }
            else -> error("fixture only drives working phases")
        }
        return run
    }
}
