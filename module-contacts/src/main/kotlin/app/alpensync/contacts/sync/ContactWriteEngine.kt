// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Drain + failure classification adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/sync/.../contacts/ContactWriteEngine.kt (push half). Deviations per
// ADR 0006 (Accepted Option B) and ADR 0007: safe order is sequential
// creates → updates → deletes (their Semaphore(4) parallelism is dropped —
// one contact per call makes ordering trivially auditable); the merge runs on
// the REAL stored canonical base and resolves server-wins deterministically
// with a Keystore-wrapped conflict copy (OutboxEntryPusher), never their
// CONFLICT-status-and-wait stalemate.

package app.alpensync.contacts.sync

import app.alpensync.core.api.http.AppVersionRejectedException
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.api.http.ProtonServerCodeException
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.OutboxEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Drains the persistent outbox (ADR 0007 Section 4), called once per sync run
 * BEFORE the pull (push-before-pull, Section 1): the subsequent pull observes
 * our own writes' new ModifyTimes and converges in one run.
 *
 * Order: creates → updates → deletes, FIFO within each (the op-type ints are
 * already in that order). Per entry, total containment: one failure never
 * aborts the run or loses sibling entries.
 *
 * Failure classification (M1 taxonomy consistent):
 *  - transport / 429 / 5xx → attempts+1, quadratic backoff
 *    `min(attempts² × 30s, 1h)`, `next_attempt_at` gates the next drain;
 *  - other 4xx / a rejected sub-response → quarantine (user requeues or
 *    discards, M4 surface);
 *  - 9001 human-verification / app-version rejection → the entry stays
 *    untouched and the exception propagates (the M1 flows fire above us);
 *  - cancellation propagates, always.
 *
 * The per-op work lives in [OutboxEntryPusher]; this class owns ordering and
 * the retry/quarantine bookkeeping ([PushOutcome] is the pusher's verdict,
 * applied here so attempt metadata is written in exactly one place).
 */
class ContactWriteEngine(
    private val accountName: String,
    private val db: AlpenSyncDatabase,
    private val pusher: OutboxEntryPusher,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun push(): WriteReport {
        val ready = db.outboxDao().listReady(accountName, clock())
            .sortedWith(compareBy({ it.opType }, { it.createdAt }))
        var report = WriteReport()
        for (entry in ready) {
            report += pushEntry(entry)
        }
        return report
    }

    /**
     * The per-entry total boundary. The broad final arm is the fail-closed
     * half of the taxonomy: an unlisted failure quarantines the entry (it is
     * preserved and surfaced) instead of killing the run — the write-side
     * twin of the adapter's total catch.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun pushEntry(entry: OutboxEntity): WriteReport {
        val outcome = try {
            pusher.push(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HumanVerificationRequiredException) {
            throw e // entry kept; the M1 HV flow fires above and the next run retries
        } catch (e: AppVersionRejectedException) {
            throw e
        } catch (e: Exception) {
            return handleFailure(entry, e)
        }
        return when (outcome) {
            is PushOutcome.Done -> outcome.report
            is PushOutcome.Retry -> recordRetry(entry, outcome.tag)
            is PushOutcome.Quarantine -> quarantine(entry, outcome.tag)
        }
    }

    private suspend fun handleFailure(entry: OutboxEntity, e: Exception): WriteReport {
        val status = (e as? ProtonServerCodeException)?.httpStatus
        val transient = if (status != null) {
            status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR_FLOOR
        } else {
            e is IOException
        }
        return if (transient) recordRetry(entry, e.javaClass.simpleName) else quarantine(entry, e.javaClass.simpleName)
    }

    /** Quadratic backoff `min(attempts² × 30s, 1h)`; `next_attempt_at` gates the next drain (ADR 0007 Section 4). */
    private suspend fun recordRetry(entry: OutboxEntity, tag: String): WriteReport {
        val attempts = entry.attempts + 1
        val backoffMs = minOf(attempts.toLong() * attempts * BACKOFF_UNIT_MS, MAX_BACKOFF_MS)
        db.outboxDao().recordFailure(entry.id, attempts, tag, clock() + backoffMs)
        SafeLog.log(SafeLog.Event.SYNC_OUTBOX_PUSH_RETRY, attempts)
        return WriteReport(retried = 1)
    }

    private suspend fun quarantine(entry: OutboxEntity, tag: String): WriteReport {
        db.outboxDao().quarantine(entry.id, tag)
        SafeLog.log(SafeLog.Event.SYNC_OUTBOX_QUARANTINED)
        return WriteReport(quarantined = 1)
    }

    companion object {
        internal const val HTTP_TOO_MANY_REQUESTS = 429
        internal const val HTTP_SERVER_ERROR_FLOOR = 500
        private const val BACKOFF_UNIT_MS = 30_000L
        private const val MAX_BACKOFF_MS = 3_600_000L
    }
}
