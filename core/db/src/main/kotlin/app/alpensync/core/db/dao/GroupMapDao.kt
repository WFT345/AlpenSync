// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.alpensync.core.db.entity.GroupMapEntity

/**
 * Label ↔ group mapping for the read-only group reconcile (ADR 0005 Section
 * 3; table folded into the M3a v2 migration per ADR 0007 Section 7).
 */
@Dao
interface GroupMapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<GroupMapEntity>)

    @Query("SELECT * FROM group_map WHERE account_name = :account")
    suspend fun listForAccount(account: String): List<GroupMapEntity>

    /** Logout wipe. */
    @Query("DELETE FROM group_map WHERE account_name = :account")
    suspend fun deleteAllForAccount(account: String)
}
