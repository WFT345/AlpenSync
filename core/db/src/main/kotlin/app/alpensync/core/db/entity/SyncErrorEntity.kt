// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A durable per-contact sync failure. contact_map.markError is an UPDATE,
 * so a failure on a contact with no mapping row yet (first pull: fetch,
 * decrypt, or provider-write failure) left no trace beyond a run counter —
 * the failure was invisible once the run ended. This table records every
 * failure regardless of mapping state; the contact's next successful apply
 * clears its row (ContactApplyStage.upsertMapping).
 *
 * [tag] is a class-simple-name-style token ("IOException", "card_failures",
 * "provider_write_missing"), never server or contact content (SafeLog rules).
 */
@Entity(
    tableName = "sync_errors",
    primaryKeys = ["account_name", "proton_contact_id"],
)
data class SyncErrorEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
)
