// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.alpensync.core.db.entity.ConflictCopyEntity

/**
 * Conflict-copy access (ADR 0006 Option B / ADR 0007 Section 7). The M3b
 * merge engine writes rows on every same-field-both-sides conflict; the M4
 * sync-log viewer lists and resolves them. Account-scoped throughout.
 */
@Dao
interface ConflictCopyDao {

    @Insert
    suspend fun insert(entry: ConflictCopyEntity): Long

    /** Every copy for one contact, newest first — the conflict history surface. */
    @Query(
        "SELECT * FROM conflict_copies WHERE account_name = :account" +
            " AND proton_contact_id = :contactId ORDER BY detected_at DESC",
    )
    suspend fun listForContact(account: String, contactId: String): List<ConflictCopyEntity>

    /** The unresolved queue the sync-log viewer badges. */
    @Query(
        "SELECT * FROM conflict_copies WHERE account_name = :account AND resolution = 0" +
            " ORDER BY detected_at",
    )
    suspend fun listPending(account: String): List<ConflictCopyEntity>

    @Query("UPDATE conflict_copies SET resolution = :resolution WHERE id = :id")
    suspend fun markResolution(id: Long, resolution: Int)

    /** Logout wipe. */
    @Query("DELETE FROM conflict_copies WHERE account_name = :account")
    suspend fun deleteAllForAccount(account: String)
}
