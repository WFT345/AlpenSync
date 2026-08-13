// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.core.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.db.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1→v2 migration test (ADR 0007 Section 7: every migration gets one).
 * Builds a raw v1 database from the exact DDL Room itself exported to
 * `schemas/.../1.json`, seeds rows in every v1 table, then opens the Room
 * database (running the auto-migration) and asserts:
 *   - every v1 row survives with its values intact;
 *   - `contact_map` gained `last_known_server_payload_hash`, defaulting NULL;
 *   - the new tables (outbox, conflict_copies, canonical_vcards, group_map)
 *     exist and accept writes;
 *   - the database version advanced to 2.
 *
 * Robolectric runs real SQLite; no MigrationTestHelper dependency (the raw
 * v1 DDL is frozen in the exported schema, so the fixture cannot drift).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        context.getDatabasePath(DB_NAME).delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(DB_NAME).delete()
    }

    @Test fun v1RowsSurviveAndNewSchemaWorks() = runTest {
        createV1DatabaseWithRows()

        val db = Room.databaseBuilder(context, AlpenSyncDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertV1RowsPreserved(db)
            assertNewTablesAcceptWrites(db)
            assertUserVersionIsTwo(db)
        } finally {
            db.close()
        }
    }

    /** Every v1 row survives, and the column v2 adds defaults to NULL. */
    private suspend fun assertV1RowsPreserved(db: AlpenSyncDatabase) {
        val mapRow = db.contactMapDao().findByProtonId("acct", "pc-1")
        assertNotNull(mapRow)
        checkNotNull(mapRow)
        assertEquals(7L, mapRow.androidRawContactId)
        assertEquals("hash-1", mapRow.contentHash)
        assertEquals("uid-1", mapRow.protonUid)
        assertNull(mapRow.lastKnownServerPayloadHash)

        assertEquals(1, db.tombstoneDao().listForAccount("acct").size)
        assertEquals(42, db.syncStateDao().get("acct")?.lastKnownTotal)
    }

    /** The four tables v2 introduces exist and are writable through their DAOs. */
    private suspend fun assertNewTablesAcceptWrites(db: AlpenSyncDatabase) {
        db.outboxDao().insert(outboxRow())
        assertEquals(1, db.outboxDao().countPending("acct"))
        db.canonicalVCardDao().upsert(
            app.alpensync.core.db.entity.CanonicalVCardEntity("acct", "pc-1", byteArrayOf(1, 2, 3), 5L),
        )
        assertEquals(1, db.canonicalVCardDao().countForAccount("acct"))
        db.conflictCopyDao().insert(
            app.alpensync.core.db.entity.ConflictCopyEntity(
                accountName = "acct",
                protonContactId = "pc-1",
                detectedAt = 9L,
                losingSide = app.alpensync.core.db.entity.ConflictCopyEntity.LosingSide.LOCAL,
            ),
        )
        assertEquals(1, db.conflictCopyDao().listPending("acct").size)
        db.groupMapDao().upsertAll(
            listOf(app.alpensync.core.db.entity.GroupMapEntity("acct", "lbl-1", 3L, "Friends", 11L)),
        )
        assertEquals("Friends", db.groupMapDao().listForAccount("acct").single().name)
    }

    /** Proof the migration actually ran rather than the DB being recreated. */
    private fun assertUserVersionIsTwo(db: AlpenSyncDatabase) {
        db.query("PRAGMA user_version", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    /** Writes the exact v1 schema Room exported (schemas/.../1.json) plus one row per v1 table. */
    private fun createV1DatabaseWithRows() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            V1_DDL.forEach(raw::execSQL)
            seedV1Rows(raw)
            raw.version = 1
        } finally {
            raw.close()
        }
    }

    /** One row per v1 table, so the migration has real data to preserve. */
    private fun seedV1Rows(raw: SQLiteDatabase) {
        raw.execSQL(
            "INSERT INTO contact_map VALUES ('acct','pc-1','uid-1',7,100,'hash-1',NULL,1,0,NULL,200)",
        )
        raw.execSQL("INSERT INTO tombstones VALUES ('acct','pc-gone',8,300,400)")
        raw.execSQL("INSERT INTO sync_state VALUES ('acct','evt-7',500,600,42)")
    }

    private fun outboxRow() = OutboxEntity(
        accountName = "acct",
        protonContactId = "pc-1",
        opType = OutboxEntity.OpType.UPDATE,
        payloadHash = "ph",
        createdAt = 1L,
    )

    private companion object {
        const val DB_NAME = "migration-test.db"

        /**
         * The exact v1 DDL Room exported (schemas/.../1.json). Frozen on
         * purpose: this is what a v1 database on a real device looks like,
         * and it is the only honest thing to migrate FROM. Regenerating it
         * from the current entities would make the test agree with whatever
         * the code does now -- which is precisely the bug it caught
         * (the v1 JSON had been rewritten to include a v2 column, so the
         * auto-migration emitted no ALTER and upgrades would have failed).
         */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `contact_map` (`account_name` TEXT NOT NULL," +
                " `proton_contact_id` TEXT NOT NULL, `proton_uid` TEXT," +
                " `android_raw_contact_id` INTEGER NOT NULL, `modify_time` INTEGER NOT NULL," +
                " `content_hash` TEXT NOT NULL, `photo_hash` TEXT, `is_verified` INTEGER NOT NULL," +
                " `sync_status` INTEGER NOT NULL, `last_error` TEXT, `last_synced_at` INTEGER NOT NULL," +
                " PRIMARY KEY(`account_name`, `proton_contact_id`))",
            "CREATE INDEX IF NOT EXISTS `index_contact_map_android_raw_contact_id`" +
                " ON `contact_map` (`android_raw_contact_id`)",
            "CREATE INDEX IF NOT EXISTS `index_contact_map_proton_uid` ON `contact_map` (`proton_uid`)",
            "CREATE TABLE IF NOT EXISTS `tombstones` (`account_name` TEXT NOT NULL," +
                " `proton_contact_id` TEXT NOT NULL, `android_raw_contact_id` INTEGER NOT NULL," +
                " `deleted_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL," +
                " PRIMARY KEY(`account_name`, `proton_contact_id`))",
            "CREATE INDEX IF NOT EXISTS `index_tombstones_expires_at` ON `tombstones` (`expires_at`)",
            "CREATE TABLE IF NOT EXISTS `sync_state` (`account_name` TEXT NOT NULL," +
                " `last_event_id` TEXT, `last_full_sync_at` INTEGER, `last_incremental_sync_at` INTEGER," +
                " `last_known_total` INTEGER NOT NULL, PRIMARY KEY(`account_name`))",
        )
    }
}
