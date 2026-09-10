// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.alpensync.core.db.entity.SyncErrorEntity

/**
 * The durable per-contact failure log: upsert on failure (the latest tag
 * wins), clear on the contact's next successful apply, wipe with the account.
 */
@Dao
interface SyncErrorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(error: SyncErrorEntity)

    @Query("SELECT * FROM sync_errors WHERE account_name = :account ORDER BY recorded_at DESC")
    suspend fun listForAccount(account: String): List<SyncErrorEntity>

    @Query("SELECT COUNT(*) FROM sync_errors WHERE account_name = :account")
    suspend fun countForAccount(account: String): Int

    /** Success path: the contact applied cleanly — its recorded failure is spent. */
    @Query("DELETE FROM sync_errors WHERE account_name = :account AND proton_contact_id = :protonContactId")
    suspend fun clear(account: String, protonContactId: String)

    @Query("DELETE FROM sync_errors WHERE account_name = :account")
    suspend fun deleteAllForAccount(account: String)
}
