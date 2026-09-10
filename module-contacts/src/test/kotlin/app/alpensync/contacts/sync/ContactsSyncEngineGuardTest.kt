// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.core.api.dto.ContactDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mass-delete-guard runs of the pull engine (ADR 0005 §1 + the 2026-09
 * amendment): the guard gates deletes against the deletable universe
 * (mapped contacts — grace-period ones included, never double-counted),
 * never the writes of contacts the server lists.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsSyncEngineGuardTest : ContactsSyncEngineTestBase() {

    @Test
    fun mass_delete_guard_aborts_before_any_delete_and_records_the_abort() = runTest {
        repeat(10) { addContact("c$it", "Name $it", "n$it@example.org") }
        newEngine().run()
        writer.applied.clear()

        // The server listing suddenly shrinks to 4 of 10 → 6 pending deletes.
        listed = (0..3).map { meta("c$it") }
        val engine = newEngine()
        val report = engine.run()

        assertEquals(SyncRunPhase.ABORTED, engine.tracker.phase)
        assertEquals(GuardAbort(pendingDeletions = 6, deletableTotal = 10), report.guardAbort)
        assertEquals("nothing tombstoned on abort", 0, report.tombstonedNow)
        assertTrue("the 4 survivors are ModifyTime-skipped — no writes at all", writer.applied.isEmpty())
        assertEquals("no tombstones created on abort", 0, db.tombstoneDao().listForAccount(ACCOUNT).size)
        assertEquals("last_known_total must not move on abort", 10, db.syncStateDao().get(ACCOUNT)?.lastKnownTotal)
        assertEquals("every mapping survives", 10, db.contactMapDao().countForAccount(ACCOUNT))
    }

    @Test
    fun guard_abort_still_keeps_the_writes_of_changed_survivors() = runTest {
        repeat(10) { addContact("c$it", "Name $it", "n$it@example.org") }
        newEngine().run()
        writer.applied.clear()

        // The listing shrinks to 4 of 10 AND the 4 survivors moved server-side:
        // the guard gates deletes, never the writes of contacts the server lists.
        listed = (0..3).map { meta("c$it", modifyTime = 2L) }
        (0..3).forEach {
            dtos["c$it"] = ContactDto(
                id = "c$it",
                modifyTime = 2L,
                cards = listOf(clearCard(vcard("Renamed $it", "x$it@example.org"))),
            )
        }
        val report = newEngine().run()

        assertEquals(GuardAbort(pendingDeletions = 6, deletableTotal = 10), report.guardAbort)
        assertEquals("the 4 survivors were rewritten before the abort", 4, report.updated)
        assertEquals("no tombstones created on abort", 0, db.tombstoneDao().listForAccount(ACCOUNT).size)
        assertEquals("every mapping survives", 10, db.contactMapDao().countForAccount(ACCOUNT))
    }

    @Test
    fun grace_period_rows_do_not_relax_the_guard() = runTest {
        repeat(10) { addContact("c$it", "Name $it", "n$it@example.org") }
        newEngine().run()

        // First shrink below the threshold: 4 of 10 vanish → tombstoned,
        // mappings kept in place for the grace period.
        listed = (0..5).map { meta("c$it") }
        newEngine().run()
        assertEquals(4, db.tombstoneDao().listForAccount(ACCOUNT).size)

        // Second shrink: 2 more vanish → 2 fresh + 4 in-grace = 6 pending
        // against a real universe of 10. Counting the 4 tombstones on top of
        // their mappings (14) would let this run proceed — it must abort.
        listed = (0..3).map { meta("c$it") }
        val report = newEngine().run()

        assertEquals(GuardAbort(pendingDeletions = 6, deletableTotal = 10), report.guardAbort)
        assertEquals("no new tombstones on abort", 4, db.tombstoneDao().listForAccount(ACCOUNT).size)
        assertEquals("every mapping survives", 10, db.contactMapDao().countForAccount(ACCOUNT))
    }
}
