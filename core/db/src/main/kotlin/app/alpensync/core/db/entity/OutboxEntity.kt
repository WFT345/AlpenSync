// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/.../db/entity/OutboxEntity.kt. Deviation: account_name on
// every row + a composite (account_name, proton_contact_id) index — our
// multi-account discipline (plan Section 5.5); pcontacts is single-account.

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent outbox for outbound contact mutations (ADR 0007 Section 4,
 * pcontacts ADR-0017 Section 5B). Each row is one pending CREATE, UPDATE, or
 * DELETE the M3b write engine pushes on a sync run.
 *
 * Transient failures (IO, 429, 5xx) increment [attempts] and push
 * [nextAttemptAt] forward with quadratic backoff; permanent 4xx quarantines
 * the row. DELETE rows additionally sit in a 1-hour grace (checked against
 * [createdAt] at push time) — the only undo, since the server has no Trash
 * (research notes Section 2.3).
 *
 * No decrypted contact content is stored here: the payload is rebuilt from
 * the canonical vCard store at push time; [payloadHash] exists for
 * dedup/coalescing only.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["account_name", "proton_contact_id"], name = "index_outbox_account_contact"),
        Index(value = ["next_attempt_at"], name = "index_outbox_next_attempt_at"),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_name") val accountName: String,
    /** Placeholder `local-<rawId>` for CREATEs until the server assigns a real ID. */
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "op_type") val opType: Int,
    @ColumnInfo(name = "payload_hash") val payloadHash: String,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "quarantined") val quarantined: Boolean = false,
) {
    /** Int, not enum, so future migrations never coordinate enum renames (pcontacts' note). */
    object OpType {
        const val CREATE = 0
        const val UPDATE = 1
        const val DELETE = 2
    }

    companion object {
        /** Delete-grace window (ADR 0007 Section 4): a DELETE row is unsendable while younger. */
        const val GRACE_PERIOD_MS = 3_600_000L
    }
}
