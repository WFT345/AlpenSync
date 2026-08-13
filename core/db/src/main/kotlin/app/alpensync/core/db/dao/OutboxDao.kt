// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/.../db/dao/OutboxDao.kt — every query gains an account
// scope (plan Section 5.5), as with our ContactMapDao.

package app.alpensync.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.alpensync.core.db.entity.OutboxEntity

/**
 * Queries for the persistent outbox (ADR 0007 Section 4). The M3b write
 * engine drains [listReady] on each sync run (FIFO by created_at over
 * non-quarantined rows whose backoff expired); failed entries advance via
 * [recordFailure] or get side-lined via [quarantine] until the user requeues.
 */
@Dao
interface OutboxDao {

    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    @Query(
        "SELECT * FROM outbox WHERE account_name = :account AND quarantined = 0" +
            " AND next_attempt_at <= :now ORDER BY created_at",
    )
    suspend fun listReady(account: String, now: Long): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE account_name = :account AND proton_contact_id = :contactId")
    suspend fun findByContact(account: String, contactId: String): List<OutboxEntity>

    @Query(
        "UPDATE outbox SET attempts = :attempts, last_error = :error, next_attempt_at = :nextAt" +
            " WHERE id = :id",
    )
    suspend fun recordFailure(id: Long, attempts: Int, error: String?, nextAt: Long)

    @Query("UPDATE outbox SET quarantined = 1, last_error = :error WHERE id = :id")
    suspend fun quarantine(id: Long, error: String?)

    /**
     * M3b coalescing (ADR 0007 Section 4): at most one UPDATE row per
     * contact — a re-edit rewrites the pending row's hash in place, keeping
     * its FIFO position and attempt state.
     */
    @Query("UPDATE outbox SET payload_hash = :payloadHash WHERE id = :id")
    suspend fun updatePayloadHash(id: Long, payloadHash: String)

    @Query("SELECT * FROM outbox WHERE account_name = :account AND quarantined = 1 ORDER BY created_at")
    suspend fun listQuarantined(account: String): List<OutboxEntity>

    /**
     * Returns a quarantined entry to the live queue (user action): clears the
     * flag, resets backoff so the next [listReady] picks it up immediately,
     * and drops the stale failure reason.
     */
    @Query(
        "UPDATE outbox SET quarantined = 0, attempts = 0, last_error = NULL, next_attempt_at = 0" +
            " WHERE id = :id AND quarantined = 1",
    )
    suspend fun requeue(id: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Re-edit-cancels-DELETE and push-success both remove a contact's rows (ADR 0007 Section 4). */
    @Query("DELETE FROM outbox WHERE account_name = :account AND proton_contact_id = :contactId")
    suspend fun deleteByContact(account: String, contactId: String)

    /** Logout wipe. */
    @Query("DELETE FROM outbox WHERE account_name = :account")
    suspend fun deleteAllForAccount(account: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE account_name = :account AND quarantined = 0")
    suspend fun countPending(account: String): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE account_name = :account AND quarantined = 1")
    suspend fun countQuarantined(account: String): Int

    /** Feeds the settings surface "N contacts will be removed from Proton in M minutes". */
    @Query("SELECT * FROM outbox WHERE account_name = :account AND op_type = 2 AND quarantined = 0")
    suspend fun listPendingDeletes(account: String): List<OutboxEntity>
}
