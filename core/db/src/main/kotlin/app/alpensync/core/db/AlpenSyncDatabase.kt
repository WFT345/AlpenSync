// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/.../db/PcontactsDatabase.kt. Deviations: v1 ships
// contact_map + tombstones + sync_state only — group_map lands with M2d's
// group reconcile as this DB's first migration; outbox is M3 (ADR 0005
// Section 7 records the deferral).

package app.alpensync.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.alpensync.core.db.dao.ContactMapDao
import app.alpensync.core.db.dao.SyncStateDao
import app.alpensync.core.db.dao.TombstoneDao
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.SyncStateEntity
import app.alpensync.core.db.entity.TombstoneEntity

/**
 * The single Room database for AlpenSync. Holds only mapping + sync metadata
 * — never decrypted contact content, never tokens (THREAT_MODEL.md; tokens
 * live in the Keystore-wrapped SecretStore per ADR 0004).
 *
 * `exportSchema = true` writes the v1 JSON dump to `:core:db/schemas/`, the
 * input a MigrationTestHelper needs once the first migration exists.
 */
@Database(
    entities = [
        ContactMapEntity::class,
        TombstoneEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AlpenSyncDatabase : RoomDatabase() {
    abstract fun contactMapDao(): ContactMapDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DATABASE_NAME = "alpensync.db"
    }
}
