// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.alpensync.core.db.entity.CanonicalVCardEntity

/**
 * Ciphertext-blob access for the encrypted canonical-vCard store (ADR 0007
 * Section 5(i)). Wrap/unwrap lives in :module-contacts' CanonicalVCardStore
 * (Keystore-backed) — this DAO only ever sees ciphertext.
 */
@Dao
interface CanonicalVCardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CanonicalVCardEntity)

    @Query(
        "SELECT * FROM canonical_vcards WHERE account_name = :account" +
            " AND proton_contact_id = :protonContactId",
    )
    suspend fun find(account: String, protonContactId: String): CanonicalVCardEntity?

    @Query(
        "DELETE FROM canonical_vcards WHERE account_name = :account" +
            " AND proton_contact_id = :protonContactId",
    )
    suspend fun delete(account: String, protonContactId: String)

    /** Logout wipe — pairs with the KEK alias deletion in the store layer. */
    @Query("DELETE FROM canonical_vcards WHERE account_name = :account")
    suspend fun deleteAllForAccount(account: String)

    @Query("SELECT COUNT(*) FROM canonical_vcards WHERE account_name = :account")
    suspend fun countForAccount(account: String): Int
}
