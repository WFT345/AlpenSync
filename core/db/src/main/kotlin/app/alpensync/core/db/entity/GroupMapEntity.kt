// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Folded into the M3a v2 migration per ADR 0007 Section 7 (M2d's standalone
// v2 never landed). Group writes stay impossible (research notes Section 2.5)
// — this table supports the read-only labels -> Groups reconcile.

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Proton contact-group label (`core/v4/labels?Type=2`) ↔ ContactsContract
 * group mapping. Read-only direction only: phone-side group edits revert on
 * the next sync (no reference implements the membership write endpoint).
 */
@Entity(
    tableName = "group_map",
    primaryKeys = ["account_name", "proton_label_id"],
)
data class GroupMapEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_label_id") val protonLabelId: String,
    @ColumnInfo(name = "android_group_id") val androidGroupId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "modify_time") val modifyTime: Long,
)
