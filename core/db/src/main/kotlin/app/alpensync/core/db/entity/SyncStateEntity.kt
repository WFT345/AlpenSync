// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/.../db/entity/SyncStateEntity.kt. Deviations: adds
// last_event_id (plan Section 5.4 — persist last-event-ID per account);
// timestamps nullable ("never synced" is a real state, not epoch 0).

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-account high-water marks for sync. [accountName] is the AccountManager
 * account name. [lastKnownTotal] feeds the mass-delete guard: if a sync run
 * would delete more than 50% of it (floor 10), the run aborts before any
 * delete — an API fault must not wipe the phone (ADR 0005 Section 1; the
 * guard pcontacts documents but never wires).
 * [lastEventId] is the M3 event-stream cursor; written by the M2d/M3 engine,
 * never read at M2c.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "last_event_id") val lastEventId: String?,
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long?,
    @ColumnInfo(name = "last_incremental_sync_at") val lastIncrementalSyncAt: Long?,
    @ColumnInfo(name = "last_known_total") val lastKnownTotal: Int,
)
