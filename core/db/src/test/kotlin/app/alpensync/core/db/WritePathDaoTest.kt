// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.db.entity.CanonicalVCardEntity
import app.alpensync.core.db.entity.ConflictCopyEntity
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.GroupMapEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trips for the remaining M3a tables: conflict copies (Option B),
 * the canonical-vCard ciphertext store, group_map (folded into v2), and the
 * new contact_map write-path members (server payload hash + UID dedup
 * lookup). Robolectric = real SQLite.
 */
@RunWith(RobolectricTestRunner::class)
class WritePathDaoTest {

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

    @Test fun conflictCopyRoundTripAndResolution() = runTest {
        val id = db.conflictCopyDao().insert(
            ConflictCopyEntity(
                accountName = "acct",
                protonContactId = "pc-1",
                detectedAt = 10L,
                losingSide = ConflictCopyEntity.LosingSide.LOCAL,
                payloadEnc = byteArrayOf(9, 9),
                fieldConflictsJson = "[]",
            ),
        )
        val stored = db.conflictCopyDao().listPending("acct").single()
        assertArrayEquals(byteArrayOf(9, 9), stored.payloadEnc)
        assertEquals(ConflictCopyEntity.Resolution.PENDING, stored.resolution)

        db.conflictCopyDao().markResolution(id, ConflictCopyEntity.Resolution.MERGED)
        assertEquals(0, db.conflictCopyDao().listPending("acct").size)
        assertEquals(
            ConflictCopyEntity.Resolution.MERGED,
            db.conflictCopyDao().listForContact("acct", "pc-1").single().resolution,
        )
    }

    @Test fun canonicalVCardCiphertextRoundTrip() = runTest {
        db.canonicalVCardDao().upsert(CanonicalVCardEntity("acct", "pc-1", byteArrayOf(1, 2, 3), 5L))
        assertArrayEquals(byteArrayOf(1, 2, 3), db.canonicalVCardDao().find("acct", "pc-1")?.vcardEnc)

        db.canonicalVCardDao().delete("acct", "pc-1")
        assertNull(db.canonicalVCardDao().find("acct", "pc-1"))

        db.canonicalVCardDao().upsert(CanonicalVCardEntity("acct-a", "pc-1", byteArrayOf(1), 1L))
        db.canonicalVCardDao().upsert(CanonicalVCardEntity("acct-b", "pc-1", byteArrayOf(2), 2L))
        db.canonicalVCardDao().deleteAllForAccount("acct-a")
        assertNull(db.canonicalVCardDao().find("acct-a", "pc-1"))
        assertEquals(1, db.canonicalVCardDao().countForAccount("acct-b"))
    }

    @Test fun groupMapRoundTripPerAccount() = runTest {
        db.groupMapDao().upsertAll(listOf(GroupMapEntity("acct", "lbl-1", 3L, "Friends", 11L)))
        assertEquals(3L, db.groupMapDao().listForAccount("acct").single().androidGroupId)
        db.groupMapDao().deleteAllForAccount("acct")
        assertEquals(0, db.groupMapDao().listForAccount("acct").size)
    }

    @Test fun serverPayloadHashUpdateAndUidDedupLookup() = runTest {
        // The M3 create flow: a placeholder row keyed local-<rawId> carries
        // the client-generated UID; the next pull finds it by that UID
        // (dedup-by-UID, research notes Section 2.4) and re-keys.
        db.contactMapDao().upsert(contactMapRow("acct", "local-7", uid = "urn:uuid:abc"))
        db.contactMapDao().updateServerPayloadHash("acct", "local-7", "serverhash")

        val row = db.contactMapDao().findByProtonUid("acct", "urn:uuid:abc")
        assertEquals("local-7", row?.protonContactId)
        assertEquals("serverhash", row?.lastKnownServerPayloadHash)

        // Rows last synced before M3a read the new column as NULL (unknown ≠ matching).
        db.contactMapDao().upsert(contactMapRow("acct", "pc-legacy", uid = null))
        assertNull(db.contactMapDao().findByProtonId("acct", "pc-legacy")?.lastKnownServerPayloadHash)
    }

    private fun contactMapRow(account: String, protonId: String, uid: String?) = ContactMapEntity(
        accountName = account,
        protonContactId = protonId,
        protonUid = uid,
        androidRawContactId = 7L,
        modifyTime = 100L,
        contentHash = "hash",
        photoHash = null,
        isVerified = true,
        syncStatus = ContactMapEntity.Status.CLEAN,
        lastError = null,
        lastSyncedAt = 200L,
    )
}
