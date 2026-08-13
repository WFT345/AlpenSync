// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/.../db/PcontactsDatabase.kt. Deviations: v1 shipped
// contact_map + tombstones + sync_state only; v2 (M3a) folds in ADR 0007
// Section 7's whole set — outbox, conflict_copies, canonical_vcards, the
// contact_map.last_known_server_payload_hash column, and group_map (M2d's
// standalone migration never landed, so it folds in here per the ADR).

package app.alpensync.core.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.alpensync.core.db.dao.CanonicalVCardDao
import app.alpensync.core.db.dao.ConflictCopyDao
import app.alpensync.core.db.dao.ContactMapDao
import app.alpensync.core.db.dao.GroupMapDao
import app.alpensync.core.db.dao.OutboxDao
import app.alpensync.core.db.dao.SyncStateDao
import app.alpensync.core.db.dao.TombstoneDao
import app.alpensync.core.db.entity.CanonicalVCardEntity
import app.alpensync.core.db.entity.ConflictCopyEntity
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.GroupMapEntity
import app.alpensync.core.db.entity.OutboxEntity
import app.alpensync.core.db.entity.SyncStateEntity
import app.alpensync.core.db.entity.TombstoneEntity

/**
 * The single Room database for AlpenSync. v1 held only mapping + sync
 * metadata — never decrypted contact content, never tokens (THREAT_MODEL.md;
 * tokens live in the Keystore-wrapped SecretStore per ADR 0004).
 *
 * v2 (M3a) adds the write-path foundations: the persistent outbox, Option-B
 * conflict copies, and the canonical-vCard store. `conflict_copies` and
 * `canonical_vcards` hold contact CONTENT — only ever Keystore-wrapped
 * ciphertext (AES-256-GCM, same construction as the token store; the wrap
 * layer lives in :module-contacts). This is the ADR 0007 Section 5(i)
 * decision and is recorded honestly in THREAT_MODEL.md / DATAFLOW.md.
 *
 * `exportSchema = true` writes each version's JSON dump to
 * `:core:db/schemas/`; the v1→v2 auto-migration is verified by a Robolectric
 * migration test that builds a raw v1 database from the exported v1 DDL.
 */
@Database(
    entities = [
        ContactMapEntity::class,
        TombstoneEntity::class,
        SyncStateEntity::class,
        OutboxEntity::class,
        ConflictCopyEntity::class,
        CanonicalVCardEntity::class,
        GroupMapEntity::class,
    ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class AlpenSyncDatabase : RoomDatabase() {
    abstract fun contactMapDao(): ContactMapDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun outboxDao(): OutboxDao
    abstract fun conflictCopyDao(): ConflictCopyDao
    abstract fun canonicalVCardDao(): CanonicalVCardDao
    abstract fun groupMapDao(): GroupMapDao

    companion object {
        const val DATABASE_NAME = "alpensync.db"
    }
}
