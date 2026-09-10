// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.db.entity.SyncErrorEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Durable sync-error rows: upsert replaces, listing is newest-first, clear
 * is per-contact, and the account wipe is scoped. These rows are the only
 * record of a failure on a contact that has no contact_map row yet.
 */
@RunWith(RobolectricTestRunner::class)
class SyncErrorDaoTest {

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

    @Test fun upsertReplacesAndListsNewestFirst() = runTest {
        db.syncErrorDao().upsert(error("acct", "pc-1", "IOException", recordedAt = 100L))
        db.syncErrorDao().upsert(error("acct", "pc-2", "card_failures", recordedAt = 200L))
        db.syncErrorDao().upsert(error("acct", "pc-1", "provider_write_missing", recordedAt = 300L))

        val rows = db.syncErrorDao().listForAccount("acct")
        assertEquals(listOf("pc-1", "pc-2"), rows.map { it.protonContactId })
        assertEquals("provider_write_missing", rows.first().tag)
        assertEquals(2, db.syncErrorDao().countForAccount("acct"))
    }

    @Test fun clearRemovesOnlyThatContact() = runTest {
        db.syncErrorDao().upsert(error("acct", "pc-1", "IOException", recordedAt = 1L))
        db.syncErrorDao().upsert(error("acct", "pc-2", "IOException", recordedAt = 2L))

        db.syncErrorDao().clear("acct", "pc-1")

        assertEquals(listOf("pc-2"), db.syncErrorDao().listForAccount("acct").map { it.protonContactId })
    }

    @Test fun deleteAllForAccountClearsOnlyThatAccount() = runTest {
        db.syncErrorDao().upsert(error("acct", "pc-1", "IOException", recordedAt = 1L))
        db.syncErrorDao().upsert(error("other", "pc-2", "IOException", recordedAt = 1L))

        db.syncErrorDao().deleteAllForAccount("acct")

        assertEquals(0, db.syncErrorDao().countForAccount("acct"))
        assertEquals(1, db.syncErrorDao().countForAccount("other"))
    }

    private fun error(account: String, protonId: String, tag: String, recordedAt: Long) = SyncErrorEntity(
        accountName = account,
        protonContactId = protonId,
        tag = tag,
        recordedAt = recordedAt,
    )
}
