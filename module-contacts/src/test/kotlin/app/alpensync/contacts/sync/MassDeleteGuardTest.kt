// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.TombstoneEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mass-delete guard boundaries (ADR 0005 open question 2: 50% threshold,
 * floor 10, abort-before-delete). "Exceed" is strict: exactly 50% proceeds.
 */
class MassDeleteGuardTest {

    @Test fun exactlyFiftyPercentProceeds() {
        assertEquals(
            MassDeleteGuard.Verdict.Proceed,
            MassDeleteGuard.check(pendingDeletions = 5, lastKnownTotal = 10),
        )
    }

    @Test fun oneOverFiftyPercentAborts() {
        val verdict = MassDeleteGuard.check(pendingDeletions = 6, lastKnownTotal = 10)
        assertEquals(MassDeleteGuard.Verdict.Abort(6, 10), verdict)
    }

    @Test fun oddTotalsRoundAgainstDeletion() {
        // 50% of 11 is 5.5 — 5 proceeds, 6 aborts (integer math, no floats).
        assertEquals(MassDeleteGuard.Verdict.Proceed, MassDeleteGuard.check(5, 11))
        assertEquals(MassDeleteGuard.Verdict.Abort(6, 11), MassDeleteGuard.check(6, 11))
    }

    @Test fun guardIsInactiveBelowTheFloor() {
        // 9 known, all 9 deleted: under the floor the account is allowed to
        // churn to zero (small accounts legitimately do).
        assertEquals(MassDeleteGuard.Verdict.Proceed, MassDeleteGuard.check(9, 9))
    }

    @Test fun floorEdgeAtExactlyTenIsActive() {
        assertEquals(MassDeleteGuard.Verdict.Abort(10, 10), MassDeleteGuard.check(10, 10))
    }

    @Test fun noKnownHistoryMeansNoGuard() {
        // First sync: last_known_total is 0 — nothing to protect.
        assertEquals(MassDeleteGuard.Verdict.Proceed, MassDeleteGuard.check(0, 0))
    }

    @Test fun emptyRemoteSetTriggersTheGuardThroughTheDiff() {
        // The API-fault scenario the guard exists for: the listing comes back
        // empty while we hold 10 live mappings — every one would be deleted.
        val diff = ContactDiff(
            newContacts = emptyList(),
            changedContacts = emptyList(),
            unchangedContacts = emptyList(),
            deletedContacts = (1..10).map { deletedContact("pc-$it") },
            stillTombstoned = emptyList(),
            restored = emptyList(),
            skippedNotSyncable = emptyList(),
        )
        assertEquals(MassDeleteGuard.Verdict.Abort(10, 10), MassDeleteGuard.check(diff, 10))
    }

    @Test fun alreadyTombstonedDeletionsCountTowardTheGuard() {
        // 4 vanished this run + 2 still in their grace period = 6 pending
        // against 10 known -> abort. Grace-period deletes are still deletes.
        val diff = ContactDiff(
            newContacts = emptyList(),
            changedContacts = emptyList(),
            unchangedContacts = emptyList(),
            deletedContacts = (1..4).map { deletedContact("pc-new-$it") },
            stillTombstoned = (1..2).map { tombstone("pc-old-$it") },
            restored = emptyList(),
            skippedNotSyncable = emptyList(),
        )
        assertEquals(MassDeleteGuard.Verdict.Abort(6, 10), MassDeleteGuard.check(diff, 10))
    }

    private fun deletedContact(protonId: String): DeletedContact = DeletedContact(
        ContactMapEntity(
            accountName = "acct",
            protonContactId = protonId,
            protonUid = null,
            androidRawContactId = 1L,
            modifyTime = 0L,
            contentHash = "h",
            photoHash = null,
            isVerified = true,
            syncStatus = 0,
            lastError = null,
            lastSyncedAt = 0L,
        ),
    )

    private fun tombstone(protonId: String) = TombstoneEntity(
        accountName = "acct",
        protonContactId = protonId,
        androidRawContactId = 1L,
        deletedAt = 0L,
        expiresAt = 1L,
    )
}
