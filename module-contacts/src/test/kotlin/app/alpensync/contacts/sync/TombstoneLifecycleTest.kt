// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.TombstoneEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tombstone lifecycle: create-on-remote-delete, inclusive expiry sweep. */
class TombstoneLifecycleTest {

    @Test fun createCopiesIdentityAndAppliesTheGracePeriod() {
        val tombstone = TombstoneLifecycle.create(deletedContact("pc-1"), nowMs = 1_000L, gracePeriodMs = 500L)
        assertEquals("acct", tombstone.accountName)
        assertEquals("pc-1", tombstone.protonContactId)
        assertEquals(42L, tombstone.androidRawContactId)
        assertEquals(1_000L, tombstone.deletedAt)
        assertEquals(1_500L, tombstone.expiresAt)
    }

    @Test fun defaultGracePeriodIsTwentyFourHours() {
        val tombstone = TombstoneLifecycle.create(deletedContact("pc-1"), nowMs = 0L)
        assertEquals(TombstoneLifecycle.DEFAULT_GRACE_PERIOD_MS, tombstone.expiresAt)
        assertEquals(24L * 60 * 60 * 1000, TombstoneLifecycle.DEFAULT_GRACE_PERIOD_MS)
    }

    @Test fun expiryIsInclusiveOfTheDeadline() {
        val tombstone = TombstoneEntity("acct", "pc-1", 42L, deletedAt = 0L, expiresAt = 1_000L)
        assertTrue(TombstoneLifecycle.expired(listOf(tombstone), nowMs = 999L).isEmpty())
        assertEquals(listOf(tombstone), TombstoneLifecycle.expired(listOf(tombstone), nowMs = 1_000L))
    }

    private fun deletedContact(protonId: String): DeletedContact = DeletedContact(
        ContactMapEntity(
            accountName = "acct",
            protonContactId = protonId,
            protonUid = null,
            androidRawContactId = 42L,
            modifyTime = 0L,
            contentHash = "h",
            photoHash = null,
            isVerified = true,
            syncStatus = ContactMapEntity.Status.CLEAN,
            lastError = null,
            lastSyncedAt = 0L,
        ),
    )
}
