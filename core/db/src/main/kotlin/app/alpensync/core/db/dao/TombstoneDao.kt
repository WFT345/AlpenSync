// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.alpensync.core.db.entity.TombstoneEntity

/**
 * Tombstone lifecycle queries (deletion grace period): create on remote
 * delete, cancel on reappearance, sweep on expiry. The engine reads
 * [listExpired] first (it needs the raw-contact IDs to delete the provider
 * rows), then [deleteExpired] clears the rows it just processed.
 */
@Dao
interface TombstoneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: TombstoneEntity)

    @Query(
        "SELECT * FROM tombstones WHERE account_name = :account AND proton_contact_id = :protonContactId",
    )
    suspend fun find(account: String, protonContactId: String): TombstoneEntity?

    @Query("SELECT * FROM tombstones WHERE account_name = :account")
    suspend fun listForAccount(account: String): List<TombstoneEntity>

    @Query("SELECT * FROM tombstones WHERE account_name = :account AND expires_at <= :nowMs")
    suspend fun listExpired(account: String, nowMs: Long): List<TombstoneEntity>

    /** Restore path: the contact reappeared on the server — cancel the pending delete. */
    @Query("DELETE FROM tombstones WHERE account_name = :account AND proton_contact_id = :protonContactId")
    suspend fun delete(account: String, protonContactId: String)

    /** Expiry sweep; returns the number of tombstones removed. */
    @Query("DELETE FROM tombstones WHERE account_name = :account AND expires_at <= :nowMs")
    suspend fun deleteExpired(account: String, nowMs: Long): Int

    @Query("DELETE FROM tombstones WHERE account_name = :account")
    suspend fun deleteAllForAccount(account: String)
}
