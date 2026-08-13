// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/.../db/entity/ContactMapEntity.kt. Deviations: composite
// (account_name, proton_contact_id) primary key — multi-account from day one
// (plan Section 5.5); separate photo_hash column so the diff engine can
// classify photo-only changes (M2c scope); the `deleted` flag moves out to
// TombstoneEntity (grace-period deletes). M3a (DB v2) added
// last_known_server_payload_hash per ADR 0007 Section 7 (pcontacts'
// MIGRATION_1_2 shape).

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * The authoritative ProtonID <-> RawContactID mapping plus the metadata the
 * sync engine needs to answer "what changed since last sync". Holds no
 * decrypted contact content — only IDs, timestamps, and hashes
 * (THREAT_MODEL.md).
 *
 * The secondary indices serve the hot lookups:
 *  - `android_raw_contact_id`: ContactsContract observer -> which Proton
 *    contact does this RawContact belong to?
 *  - `proton_uid`: vCard UID dedup across re-imports.
 *
 * [contentHash] covers the projected contact EXCLUDING the photo;
 * [photoHash] covers the inline photo bytes bit-exactly (null = no photo).
 * Splitting them lets the differ report a photo-only change (cheaper writer
 * path) instead of a full child-row rewrite.
 */
@Entity(
    tableName = "contact_map",
    primaryKeys = ["account_name", "proton_contact_id"],
    indices = [Index("android_raw_contact_id"), Index("proton_uid")],
)
data class ContactMapEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "proton_uid") val protonUid: String?,
    @ColumnInfo(name = "android_raw_contact_id") val androidRawContactId: Long,
    @ColumnInfo(name = "modify_time") val modifyTime: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "photo_hash") val photoHash: String?,
    @ColumnInfo(name = "is_verified") val isVerified: Boolean,
    @ColumnInfo(name = "sync_status") val syncStatus: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long,
    /**
     * Hash of the server payload as last seen (ADR 0007 Section 5): the
     * divergence hint the M3 push path consults before writing — if the
     * server's current payload no longer matches, the contact changed
     * server-side since our last pull and the merge machinery engages.
     * Null for rows last synced before M3a (DB v2) — treated as "unknown",
     * never as "matching".
     */
    @ColumnInfo(name = "last_known_server_payload_hash") val lastKnownServerPayloadHash: String? = null,
) {
    /**
     * `sync_status` is an Int rather than an enum so future schema migrations
     * don't coordinate enum renames (pcontacts' note). M2 uses CLEAN and
     * ERROR only; PENDING_PULL/PENDING_PUSH/CONFLICT are the M3 write-path
     * states (ADR 0005 Section 3 reserved the numeric gaps for them).
     */
    object Status {
        const val CLEAN = 0
        const val PENDING_PULL = 1
        const val PENDING_PUSH = 2
        const val CONFLICT = 3
        const val ERROR = 4
    }
}
