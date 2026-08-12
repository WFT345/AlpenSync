// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.db.entity.SyncStateEntity
import app.alpensync.core.db.entity.TombstoneEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tombstone lifecycle + sync-state round trips under Robolectric: grace
 * creation, restore-cancel, expiry sweep boundaries, and per-account state.
 */
@RunWith(RobolectricTestRunner::class)
class TombstoneAndSyncStateDaoTest {

    private lateinit var db: AlpenSyncDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test fun tombstoneRoundTripsAndCancelsOnRestore() = runTest {
        db.tombstoneDao().upsert(tombstone("acct", "pc-1", expiresAt = 1_000L))
        assertEquals(1_000L, db.tombstoneDao().find("acct", "pc-1")?.expiresAt)
        db.tombstoneDao().delete("acct", "pc-1")
        assertNull(db.tombstoneDao().find("acct", "pc-1"))
    }

    @Test fun expirySweepIsInclusiveAndAccountScoped() = runTest {
        db.tombstoneDao().upsert(tombstone("acct", "pc-expired", expiresAt = 1_000L))
        db.tombstoneDao().upsert(tombstone("acct", "pc-alive", expiresAt = 2_000L))
        db.tombstoneDao().upsert(tombstone("other", "pc-expired", expiresAt = 1_000L))
        assertEquals(0, db.tombstoneDao().listExpired("acct", 999L).size)
        assertEquals(listOf("pc-expired"), db.tombstoneDao().listExpired("acct", 1_000L).map { it.protonContactId })
        assertEquals(1, db.tombstoneDao().deleteExpired("acct", 1_000L))
        assertEquals(listOf("pc-alive"), db.tombstoneDao().listForAccount("acct").map { it.protonContactId })
        assertEquals(1, db.tombstoneDao().listForAccount("other").size)
    }

    @Test fun deleteAllForAccountClearsOnlyThatAccount() = runTest {
        db.tombstoneDao().upsert(tombstone("acct", "pc-1", expiresAt = 1L))
        db.tombstoneDao().upsert(tombstone("other", "pc-2", expiresAt = 1L))
        db.tombstoneDao().deleteAllForAccount("acct")
        assertEquals(0, db.tombstoneDao().listForAccount("acct").size)
        assertEquals(1, db.tombstoneDao().listForAccount("other").size)
    }

    @Test fun syncStateRoundTripsIncludingTheEventCursor() = runTest {
        assertNull(db.syncStateDao().get("acct"))
        db.syncStateDao().upsert(
            SyncStateEntity(
                accountName = "acct",
                lastEventId = "evt-9",
                lastFullSyncAt = 100L,
                lastIncrementalSyncAt = null,
                lastKnownTotal = 42,
            ),
        )
        val state = db.syncStateDao().get("acct")
        assertEquals("evt-9", state?.lastEventId)
        assertEquals(42, state?.lastKnownTotal)
        assertNull(state?.lastIncrementalSyncAt)
        db.syncStateDao().delete("acct")
        assertNull(db.syncStateDao().get("acct"))
    }

    private fun tombstone(account: String, protonId: String, expiresAt: Long) = TombstoneEntity(
        accountName = account,
        protonContactId = protonId,
        androidRawContactId = 1L,
        deletedAt = 0L,
        expiresAt = expiresAt,
    )
}
