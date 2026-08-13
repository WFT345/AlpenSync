// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ConflictCopyEntity
import app.alpensync.core.db.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountContentWiperTest {

    private lateinit var db: AlpenSyncDatabase
    private var keyDeleted = false
    private lateinit var store: CanonicalVCardStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        keyDeleted = false
        store = CanonicalVCardStore(db.canonicalVCardDao(), { it }, { it }, { keyDeleted = true })
    }

    @After
    fun tearDown() = db.close()

    @Test fun wipe_drops_canonical_conflict_and_outbox_rows_and_the_kek() = runTest {
        store.write(ACCOUNT, "pc-1", "BEGIN:VCARD\r\nEND:VCARD\r\n")
        db.conflictCopyDao().insert(
            ConflictCopyEntity(
                accountName = ACCOUNT,
                protonContactId = "pc-1",
                detectedAt = 1L,
                losingSide = ConflictCopyEntity.LosingSide.LOCAL,
                payloadEnc = byteArrayOf(1),
            ),
        )
        db.outboxDao().insert(
            OutboxEntity(
                accountName = ACCOUNT,
                protonContactId = "pc-1",
                opType = OutboxEntity.OpType.UPDATE,
                payloadHash = "h",
                createdAt = 1L,
            ),
        )

        AccountContentWiper.wipe(db, store, ACCOUNT)

        assertFalse(store.exists(ACCOUNT, "pc-1"))
        assertTrue(db.conflictCopyDao().listForContact(ACCOUNT, "pc-1").isEmpty())
        assertEquals(0, db.outboxDao().countPending(ACCOUNT))
        assertTrue(keyDeleted)
    }

    private companion object {
        const val ACCOUNT = "default"
    }
}
