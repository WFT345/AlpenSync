// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Orchestration shape adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/sync/.../ContactDetailSyncEngine.kt. Deviations: the mass-delete
// guard is actually wired (theirs is documented but never called — research
// notes §4.3 warning); remote deletes go through the M2c tombstone grace
// period instead of immediate provider deletes; a contact with ANY card
// failure is an error (never a partial write), which is stricter than
// pcontacts' drop-the-card-and-continue.

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.ContactDecrypter
import app.alpensync.contacts.vcard.VCardMerger
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.core.api.dto.ContactDto
import app.alpensync.core.api.dto.ContactMetadataDto
import app.alpensync.core.api.http.AppVersionRejectedException
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.SyncStateEntity
import app.alpensync.core.db.entity.TombstoneEntity
import java.io.IOException

/**
 * The M2 one-way pipeline, one run of it (ADR 0005 Section 1):
 *
 *   metadata walk → two-tier skip → per-ID fetch → decrypt+merge → persist
 *   that contact → mass-delete guard → tombstone create/sweep → SyncReport
 *
 * Each fetched contact is written to the provider and Room before the next
 * fetch starts. A killed first pull keeps what it already landed; the next
 * run skips those via ModifyTime. Deletes still wait for the mass-delete
 * guard so a truncated listing cannot wipe the phone.
 *
 * Fail-closed throughout (plan Rule 5):
 *  - the guard aborts BEFORE any delete is applied when the pending deletes
 *    exceed 50%/floor-10 of the deletable set (mappings + tombstones), and
 *    the abort is recorded in the report + SafeLog;
 *  - a per-contact fetch/decrypt/parse failure marks the mapping row ERROR
 *    and continues — that contact is never deleted and never half-written;
 *  - a 9001 / app-version rejection aborts the whole run (every subsequent
 *    call hits the same gate — pcontacts' shipped policy);
 *  - group reconcile (labels → ContactsContract.Groups) is deliberately NOT
 *    here: it needs the `group_map` table, the recorded DB v2 follow-up.
 *
 * The three pipeline stages are constructor-injected so unit tests drive the
 * engine with fakes; production wiring lives in ContactsSyncBootstrap.
 *
 * M3b additions (ADR 0007): contacts the write path owns (PENDING_PUSH /
 * CONFLICT status or a local-create placeholder mapping) are SKIPPED here —
 * the push-side three-way merge owns their convergence, and a pull overwrite
 * would silently destroy a queued local edit; every applied contact persists
 * its canonical vCard + server payload hash (CanonicalPersistence) so the
 * write path has a merge base; a new server contact whose UID matches a
 * placeholder collapses into the pending create (lost-create-response dedup,
 * ADR 0007 Section 3) instead of writing a duplicate.
 */
class ContactsSyncEngine(
    private val accountName: String,
    private val listMetadata: suspend () -> List<ContactMetadataDto>,
    private val fetchContact: suspend (String) -> ContactDto,
    private val decrypter: ContactDecrypter,
    private val writer: ContactsWriterGateway,
    stores: ContactsSyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Phase observability for tests and the future sync-log viewer. */
    val tracker = SyncRunTracker()

    private val db = stores.db
    private val applyStage = ContactApplyStage(
        accountName,
        stores.db,
        writer,
        CanonicalPersistence(accountName, stores.db, stores.canonical),
    )

    suspend fun run(): SyncReport {
        val stats = PullRunStats()
        var listedCount = 0
        tracker.report(SyncProgress(inFlight = true))
        try {
            return runPull(stats) { listedCount = it }
        } finally {
            tracker.report(
                SyncProgress(
                    listed = listedCount,
                    processed = stats.processed,
                    applied = stats.inserted + stats.updated,
                    inFlight = false,
                ),
            )
        }
    }

    private suspend fun runPull(stats: PullRunStats, rememberListed: (Int) -> Unit): SyncReport {
        tracker.transition(SyncRunPhase.LISTING)
        val listed = listMetadata()
        rememberListed(listed.size)
        emitProgress(listed.size, processed = 0, stats)
        tracker.transition(SyncRunPhase.DIFFING)
        val run = PullRun(listed, loadSnapshot(listed), clock(), stats)
        val canonicals = fetchStage(run)
        val listedIds = listed.mapTo(HashSet()) { it.id }
        val diff = ContactDiffer.diff(canonicals, run.snap.syncableMappings, run.snap.tombstones, listedIds)
        return finishAfterFetch(diff, run)
    }

    private suspend fun finishAfterFetch(diff: ContactDiff, run: PullRun): SyncReport {
        tracker.transition(SyncRunPhase.GUARD_CHECK)
        val verdict = MassDeleteGuard.check(diff, run.snap.deletableTotal)
        if (verdict is MassDeleteGuard.Verdict.Abort) {
            tracker.transition(SyncRunPhase.ABORTED)
            SafeLog.log(SafeLog.Event.SYNC_GUARD_ABORTED, verdict.pendingDeletions)
            return buildReport(
                diff,
                run.listed.size,
                run.stats,
                GuardAbort(verdict.pendingDeletions, verdict.deletableTotal),
                tombstonedNow = 0, // nothing was tombstoned — the abort pre-empted the sweep
            )
        }
        // New/changed contacts already landed per-fetch; APPLYING stays as a
        // phase so the state machine and any sync-log viewer keep their shape.
        tracker.transition(SyncRunPhase.APPLYING)
        tracker.transition(SyncRunPhase.SWEEPING)
        applyStage.sweep(diff, run.snap.tombstones, run.nowMs, run.stats)
        finishState(run.listed.size, run.nowMs)
        tracker.transition(SyncRunPhase.COMPLETED)
        return buildReport(diff, run.listed.size, run.stats, guardAbort = null)
    }

    private suspend fun loadSnapshot(listed: List<ContactMetadataDto>): PullSnapshot {
        val mappings = db.contactMapDao().listForAccount(accountName)
        val writePendingIds = mappings.filter { it.isWritePending() }.mapTo(HashSet()) { it.protonContactId }
        val syncable = if (writePendingIds.isEmpty()) {
            mappings
        } else {
            mappings.filter { it.protonContactId !in writePendingIds }
        }
        return PullSnapshot(
            mappingsById = mappings.associateBy { it.protonContactId },
            writePendingIds = writePendingIds,
            syncableMappings = syncable,
            tombstones = db.tombstoneDao().listForAccount(accountName),
            modifyTimeById = listed.associate { it.id to it.modifyTime },
            placeholders = applyStage.placeholdersByUid(mappings),
            // The run's only full provider scan: per-contact applies keep this
            // map current from ApplyResult instead of re-querying each time.
            knownRawIds = writer.readExistingRawIds().toMutableMap(),
        )
    }

    /**
     * Two-tier skip (research notes §4.2 step 4): (a) server ModifyTime didn't
     * advance past the stored one → refresh bookkeeping only; (b) else fetch +
     * decrypt + merge, and the differ's hash check catches ModifyTime bumps
     * without visible changes. Steady state on an unchanged account costs one
     * listing call and zero fetches.
     *
     * A TOMBSTONED contact never takes the skip: it must be re-processed so
     * the differ can report its restore (cancelling the pending delete) —
     * skipping it would let the grace period expire under a contact the
     * server lists again.
     */
    private suspend fun fetchStage(run: PullRun): List<CanonicalContact> {
        val tombstonedIds = run.snap.tombstones.mapTo(HashSet()) { it.protonContactId }
        val canonicals = ArrayList<CanonicalContact>(run.listed.size)
        for ((index, meta) in run.listed.withIndex()) {
            handleListed(meta, run, tombstonedIds, canonicals)
            run.stats.processed = index + 1
            emitProgress(run.listed.size, run.stats.processed, run.stats)
        }
        return canonicals
    }

    private suspend fun handleListed(
        meta: ContactMetadataDto,
        run: PullRun,
        tombstonedIds: Set<String>,
        canonicals: MutableList<CanonicalContact>,
    ) {
        // The push side owns a write-pending contact's convergence: the
        // three-way merge fetches the server state itself, and a pull
        // overwrite here would destroy the queued local edit.
        if (meta.id in run.snap.writePendingIds) return
        val mapping = run.snap.mappingsById[meta.id]
        if (mapping != null && isSkippable(meta, mapping, tombstonedIds)) {
            db.contactMapDao().refreshBookkeeping(accountName, meta.id, mapping.modifyTime, run.nowMs)
            run.stats.unchanged++
            return
        }
        val canonical = fetchOne(meta.id, run.stats) ?: return
        canonicals += canonical
        persistOne(canonical, mapping, run)
    }

    /** Write this contact before the next fetch. Deletes stay with the guard. */
    private suspend fun persistOne(
        canonical: CanonicalContact,
        mapping: ContactMapEntity?,
        run: PullRun,
    ) {
        val one = ContactDiffer.diff(
            listOf(canonical),
            listOfNotNull(mapping),
            run.snap.tombstones,
            setOf(canonical.protonContactId),
        )
        applyStage.apply(one, listOf(canonical), run.snap, run.nowMs, run.stats)
    }

    /** Two-tier skip: server ModifyTime didn't advance; a tombstoned contact never skips. */
    private fun isSkippable(meta: ContactMetadataDto, mapping: ContactMapEntity, tombstonedIds: Set<String>): Boolean =
        meta.modifyTime <= mapping.modifyTime && meta.id !in tombstonedIds

    /** Null return = the contact failed loudly and was counted; never a silent drop. */
    private suspend fun fetchOne(protonContactId: String, stats: PullRunStats): CanonicalContact? {
        val dto = try {
            fetchContact(protonContactId)
        } catch (e: IOException) {
            // 9001 / app-version rejection gate every subsequent call — abort
            // the run by rethrowing (the SyncAdapter maps them to auth errors).
            if (e is HumanVerificationRequiredException || e is AppVersionRejectedException) throw e
            applyStage.markError(protonContactId, e.javaClass.simpleName, stats)
            return null
        } catch (e: IllegalArgumentException) {
            // Strict DTO parsing failing closed (Rule 5) — the API shape moved.
            applyStage.markError(protonContactId, e.javaClass.simpleName, stats)
            return null
        }
        stats.fetched++
        val result = decrypter.decryptContact(dto.cards)
        if (result.failures.isNotEmpty()) {
            stats.cardFailures += result.failures.size
            applyStage.markError(protonContactId, "card_failures", stats)
            return null
        }
        val canonical = VCardMerger.merge(protonContactId, result.cards)
        if (!canonical.verified) stats.unverifiedContacts++
        return canonical
    }

    private suspend fun finishState(listedTotal: Int, nowMs: Long) {
        val existing = db.syncStateDao().get(accountName)
        db.syncStateDao().upsert(
            SyncStateEntity(
                accountName = accountName,
                lastEventId = existing?.lastEventId,
                lastFullSyncAt = nowMs,
                lastIncrementalSyncAt = existing?.lastIncrementalSyncAt,
                lastKnownTotal = listedTotal,
            ),
        )
    }

    private fun emitProgress(listed: Int, processed: Int, stats: PullRunStats) {
        tracker.report(
            SyncProgress(
                listed = listed,
                processed = processed,
                applied = stats.inserted + stats.updated,
                inFlight = true,
            ),
        )
    }

    private fun buildReport(
        diff: ContactDiff,
        listed: Int,
        stats: PullRunStats,
        guardAbort: GuardAbort?,
        tombstonedNow: Int = diff.deletedContacts.size,
    ): SyncReport =
        SyncReport(
            listed = listed,
            fetched = stats.fetched,
            inserted = stats.inserted,
            updated = stats.updated,
            unchanged = stats.unchanged,
            tombstonedNow = tombstonedNow,
            tombstonedPending = diff.stillTombstoned.size,
            swept = stats.swept,
            restored = diff.restored.size,
            skippedNotSyncable = diff.skippedNotSyncable.size,
            contactErrors = stats.contactErrors,
            cardFailures = stats.cardFailures,
            unverifiedContacts = stats.unverifiedContacts,
            guardAbort = guardAbort,
            phase = tracker.phase,
        )

    /** Everything one pull run threads through its stages. */
    private data class PullRun(
        val listed: List<ContactMetadataDto>,
        val snap: PullSnapshot,
        val nowMs: Long,
        val stats: PullRunStats,
    )
}

/**
 * The run-start read model: Room state plus the provider's SOURCE_ID → raw-ID
 * map (read ONCE here; per-contact applies keep it current from ApplyResult).
 */
internal data class PullSnapshot(
    val mappingsById: Map<String, ContactMapEntity>,
    val writePendingIds: Set<String>,
    val syncableMappings: List<ContactMapEntity>,
    val tombstones: List<TombstoneEntity>,
    val modifyTimeById: Map<String, Long>,
    val placeholders: Map<String, ContactMapEntity>,
    val knownRawIds: MutableMap<String, Long>,
) {
    /** What this run could delete — the mass-delete guard's denominator. */
    val deletableTotal: Int get() = syncableMappings.size + tombstones.size
}

/** Mutable per-run counters; the report is built from it at the end. */
internal class PullRunStats {
    var processed = 0
    var fetched = 0
    var inserted = 0
    var updated = 0
    var unchanged = 0
    var swept = 0
    var contactErrors = 0
    var cardFailures = 0
    var unverifiedContacts = 0
}

/**
 * Write-path-owned rows (ADR 0007 Sections 2/5): a queued local edit
 * (PENDING_PUSH), a recorded conflict (CONFLICT), or a local-create
 * placeholder — the pull engine must not rewrite these from server state;
 * the push-side merge owns their convergence.
 */
private fun ContactMapEntity.isWritePending(): Boolean =
    syncStatus == ContactMapEntity.Status.PENDING_PUSH ||
        syncStatus == ContactMapEntity.Status.CONFLICT ||
        LocalChangeDetector.isLocalPlaceholder(protonContactId)
