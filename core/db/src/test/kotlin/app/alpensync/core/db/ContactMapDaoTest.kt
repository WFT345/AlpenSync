// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.db.entity.ContactMapEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room round-trip tests for the contact mapping. Runs under Robolectric
 * (real SQLite, no emulator) — the trade-off: Room's generated SQL is
 * exercised, the real device's SQLite version is not.
 */
@RunWith(RobolectricTestRunner::class)
class ContactMapDaoTest {

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

    @Test fun upsertThenFindByEveryLookupKey() = runTest {
        db.contactMapDao().upsert(entry("acct", "pc-1", rawId = 7L, uid = "uid-1"))
        assertEquals(7L, db.contactMapDao().findByProtonId("acct", "pc-1")?.androidRawContactId)
        assertEquals("pc-1", db.contactMapDao().findByRawContactId("acct", 7L)?.protonContactId)
        assertEquals("pc-1", db.contactMapDao().findByProtonUid("acct", "uid-1")?.protonContactId)
        assertEquals(1, db.contactMapDao().countForAccount("acct"))
    }

    @Test fun upsertReplacesOnPrimaryKeyConflict() = runTest {
        db.contactMapDao().upsert(entry("acct", "pc-1", contentHash = "old"))
        db.contactMapDao().upsert(entry("acct", "pc-1", contentHash = "new"))
        assertEquals("new", db.contactMapDao().findByProtonId("acct", "pc-1")?.contentHash)
        assertEquals(1, db.contactMapDao().countForAccount("acct"))
    }

    @Test fun accountsAreIsolated() = runTest {
        db.contactMapDao().upsert(entry("acct-a", "pc-1"))
        db.contactMapDao().upsert(entry("acct-b", "pc-1", contentHash = "other"))
        assertEquals("h", db.contactMapDao().findByProtonId("acct-a", "pc-1")?.contentHash)
        assertEquals("other", db.contactMapDao().findByProtonId("acct-b", "pc-1")?.contentHash)
        db.contactMapDao().deleteAllForAccount("acct-a")
        assertNull(db.contactMapDao().findByProtonId("acct-a", "pc-1"))
        assertEquals(1, db.contactMapDao().countForAccount("acct-b"))
    }

    @Test fun refreshBookkeepingMovesOnlyTimestamps() = runTest {
        db.contactMapDao().upsert(entry("acct", "pc-1", modifyTime = 1L, syncedAt = 2L))
        db.contactMapDao().refreshBookkeeping("acct", "pc-1", modifyTime = 10L, lastSyncedAt = 20L)
        val row = db.contactMapDao().findByProtonId("acct", "pc-1")
        assertEquals(10L, row?.modifyTime)
        assertEquals(20L, row?.lastSyncedAt)
        assertEquals("h", row?.contentHash)
        assertEquals(ContactMapEntity.Status.CLEAN, row?.syncStatus)
    }

    @Test fun markErrorSetsStatusAndKeepsTheRow() = runTest {
        db.contactMapDao().upsert(entry("acct", "pc-1"))
        db.contactMapDao().markError("acct", "pc-1", "card decrypt failed")
        val row = db.contactMapDao().findByProtonId("acct", "pc-1")
        assertEquals(ContactMapEntity.Status.ERROR, row?.syncStatus)
        assertEquals("card decrypt failed", row?.lastError)
    }

    @Test fun deleteByProtonIdRemovesOnlyThatRow() = runTest {
        db.contactMapDao().upsertAll(listOf(entry("acct", "pc-1"), entry("acct", "pc-2", rawId = 8L)))
        db.contactMapDao().deleteByProtonId("acct", "pc-1")
        assertNull(db.contactMapDao().findByProtonId("acct", "pc-1"))
        assertEquals(listOf("pc-2"), db.contactMapDao().listForAccount("acct").map { it.protonContactId })
    }

    @Test fun emptyAccountListsAndCountsNothing() = runTest {
        assertTrue(db.contactMapDao().listForAccount("nobody").isEmpty())
        assertEquals(0, db.contactMapDao().countForAccount("nobody"))
    }

    private fun entry(
        account: String,
        protonId: String,
        rawId: Long = 1L,
        uid: String? = null,
        contentHash: String = "h",
        modifyTime: Long = 0L,
        syncedAt: Long = 0L,
    ) = ContactMapEntity(
        accountName = account,
        protonContactId = protonId,
        protonUid = uid,
        androidRawContactId = rawId,
        modifyTime = modifyTime,
        contentHash = contentHash,
        photoHash = null,
        isVerified = true,
        syncStatus = ContactMapEntity.Status.CLEAN,
        lastError = null,
        lastSyncedAt = syncedAt,
    )
}
