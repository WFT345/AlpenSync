// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Informed by pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// contact_map.deleted flag + outbox grace period — restructured here as a
// first-class table so a remote delete is reversible until the grace period
// expires (M2c scope decision; research notes Section 4.4).

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A remotely-deleted contact inside its deletion grace period. While a
 * tombstone exists, the provider row and the contact_map row are LEFT IN
 * PLACE: if the contact reappears on the server before [expiresAt], the
 * tombstone is cancelled and nothing was ever rewritten. On expiry the
 * engine deletes the provider row ([androidRawContactId]) and the mapping
 * row, then the tombstone itself.
 *
 * [androidRawContactId] is duplicated from contact_map deliberately: the
 * sweep must know which provider row to delete without a join.
 */
@Entity(
    tableName = "tombstones",
    primaryKeys = ["account_name", "proton_contact_id"],
    indices = [Index("expires_at")],
)
data class TombstoneEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "android_raw_contact_id") val androidRawContactId: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)
