// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Shape per ADR 0007 Section 7 (Option B, ADR 0006 Accepted): the losing
// version of a conflicted contact is always preserved and exportable —
// the never-silent rule: no conflict path drops user data without a
// recoverable copy + a sync-log entry.

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The losing side of a three-way-merge conflict (ADR 0006 Option B): when the
 * same field changed on both sides, the server value wins and the losing
 * field values land here for user review/export (M4 sync-log viewer consumes;
 * M3b writes the rows).
 *
 * [payloadEnc] holds the losing contact's canonical vCard encrypted with the
 * same Keystore-backed AES-256-GCM construction as the canonical store
 * (ADR 0007 Section 5(i), THREAT_MODEL.md) — never plaintext at rest.
 * [fieldConflictsJson] carries the per-field detail (which fields conflicted,
 * each side's value) for the sync-log surface.
 */
@Entity(
    tableName = "conflict_copies",
    indices = [
        Index(value = ["account_name", "proton_contact_id"], name = "index_conflict_copies_contact"),
    ],
)
data class ConflictCopyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    @ColumnInfo(name = "resolution") val resolution: Int = Resolution.PENDING,
    @ColumnInfo(name = "losing_side") val losingSide: Int,
    @ColumnInfo(name = "payload_enc") val payloadEnc: ByteArray? = null,
    @ColumnInfo(name = "field_conflicts_json") val fieldConflictsJson: String? = null,
) {
    object Resolution {
        const val PENDING = 0
        const val SERVER_WON = 1
        const val LOCAL_WON = 2
        const val MERGED = 3
    }

    object LosingSide {
        const val SERVER = 1
        const val LOCAL = 2
    }
}
