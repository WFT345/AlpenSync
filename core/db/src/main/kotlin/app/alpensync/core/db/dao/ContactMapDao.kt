// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/.../db/dao/ContactMapDao.kt — every query gains an
// account scope; conflict/listConflicts and the M3-only queries are dropped
// (Rule 14: they arrive with M3); refreshBookkeeping added for the
// two-tier-skip hot path (research notes Section 4.2 step 4).

package app.alpensync.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.alpensync.core.db.entity.ContactMapEntity

/**
 * Hot-path queries for the sync engine, exactly the operations it needs and
 * no more. `upsert` uses REPLACE so a post-write reconcile never does a
 * delete+insert two-step. Every query is account-scoped (plan Section 5.5).
 */
@Dao
interface ContactMapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ContactMapEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ContactMapEntity>)

    @Query(
        "SELECT * FROM contact_map WHERE account_name = :account" +
            " AND proton_contact_id = :protonContactId",
    )
    suspend fun findByProtonId(account: String, protonContactId: String): ContactMapEntity?

    @Query(
        "SELECT * FROM contact_map WHERE account_name = :account" +
            " AND android_raw_contact_id = :rawContactId",
    )
    suspend fun findByRawContactId(account: String, rawContactId: Long): ContactMapEntity?

    @Query(
        "SELECT * FROM contact_map WHERE account_name = :account" +
            " AND proton_uid = :protonUid LIMIT 1",
    )
    suspend fun findByProtonUid(account: String, protonUid: String): ContactMapEntity?

    /** The diff engine's mapping-table snapshot for one account. */
    @Query("SELECT * FROM contact_map WHERE account_name = :account")
    suspend fun listForAccount(account: String): List<ContactMapEntity>

    /**
     * Two-tier-skip bookkeeping refresh (research notes Section 4.2 step 4):
     * the server ModifyTime advanced but the content hash still matches —
     * only the timestamps move, hashes and status stay untouched.
     */
    @Query(
        "UPDATE contact_map SET modify_time = :modifyTime, last_synced_at = :lastSyncedAt" +
            " WHERE account_name = :account AND proton_contact_id = :protonContactId",
    )
    suspend fun refreshBookkeeping(
        account: String,
        protonContactId: String,
        modifyTime: Long,
        lastSyncedAt: Long,
    )

    /** Per-contact failure: skip-and-continue, but the row records the error. */
    @Query(
        "UPDATE contact_map SET sync_status = 4, last_error = :lastError" +
            " WHERE account_name = :account AND proton_contact_id = :protonContactId",
    )
    suspend fun markError(account: String, protonContactId: String, lastError: String)

    /**
     * M3b write path (ADR 0007 Section 2): a local edit was captured into the
     * outbox. While a contact is PENDING_PUSH the pull engine must not
     * rewrite its provider rows from the server state — the push-side
     * three-way merge owns the convergence, and a pull overwrite would
     * silently destroy the queued local edit.
     */
    @Query(
        "UPDATE contact_map SET sync_status = 2 WHERE account_name = :account" +
            " AND proton_contact_id = :protonContactId",
    )
    suspend fun markPendingPush(account: String, protonContactId: String)

    /**
     * M3b write path: a pending push was cancelled BEFORE any server write
     * (the edit was reverted to the baseline, or a pending delete was
     * un-done by a re-edit) — the contact returns to the normal pull path.
     */
    @Query(
        "UPDATE contact_map SET sync_status = 0 WHERE account_name = :account" +
            " AND proton_contact_id = :protonContactId",
    )
    suspend fun markClean(account: String, protonContactId: String)

    /**
     * M3 write path (ADR 0007 Section 5): records the server payload hash
     * after every successful pull/push of the contact — the divergence hint
     * the push consults before writing.
     */
    @Query(
        "UPDATE contact_map SET last_known_server_payload_hash = :hash" +
            " WHERE account_name = :account AND proton_contact_id = :protonContactId",
    )
    suspend fun updateServerPayloadHash(account: String, protonContactId: String, hash: String?)

    @Query(
        "DELETE FROM contact_map WHERE account_name = :account AND proton_contact_id = :protonContactId",
    )
    suspend fun deleteByProtonId(account: String, protonContactId: String)

    /** Logout wipe — provider rows go through the writer, this clears the mapping. */
    @Query("DELETE FROM contact_map WHERE account_name = :account")
    suspend fun deleteAllForAccount(account: String)

    @Query("SELECT COUNT(*) FROM contact_map WHERE account_name = :account")
    suspend fun countForAccount(account: String): Int
}
