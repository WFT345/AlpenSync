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

import androidx.room.withTransaction
import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.ContactDecrypter
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.VCardMerger
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.ContactDto
import app.alpensync.core.api.dto.ContactMetadataDto
import app.alpensync.core.api.http.AppVersionRejectedException
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.SyncStateEntity
import app.alpensync.core.db.entity.TombstoneEntity
import java.io.IOException

/**
 * The M2 one-way pipeline, one run of it (ADR 0005 Section 1):
 *
 *   metadata walk → two-tier skip → per-ID fetch → decrypt+merge → diff →
 *   mass-delete guard → chunked apply → tombstone create/sweep → mapping +
 *   sync-state reconcile → SyncReport
 *
 * Fail-closed throughout (plan Rule 5):
 *  - the guard aborts BEFORE any provider write when the listing shrinks
 *    past 50%/floor-10, and the abort is recorded in the report + SafeLog;
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
    private val canonicalPersistence = CanonicalPersistence(accountName, stores.db, stores.canonical)

    suspend fun run(): SyncReport {
        tracker.transition(SyncRunPhase.LISTING)
        val listed = listMetadata()
        tracker.transition(SyncRunPhase.DIFFING)
        val nowMs = clock()
        val mappings = db.contactMapDao().listForAccount(accountName)
        val writePendingIds = mappings.filter { it.isWritePending() }.mapTo(HashSet()) { it.protonContactId }
        val syncableMappings = if (writePendingIds.isEmpty()) {
            mappings
        } else {
            mappings.filter { it.protonContactId !in writePendingIds }
        }
        val tombstones = db.tombstoneDao().listForAccount(accountName)
        val lastKnownTotal = db.syncStateDao().get(accountName)?.lastKnownTotal ?: 0
        val stats = RunStats()
        val canonicals = fetchStage(listed, syncableMappings, writePendingIds, tombstones, nowMs, stats)
        val diff = ContactDiffer.diff(canonicals, syncableMappings, tombstones, listed.mapTo(HashSet()) { it.id })

        tracker.transition(SyncRunPhase.GUARD_CHECK)
        val verdict = MassDeleteGuard.check(diff, lastKnownTotal)
        if (verdict is MassDeleteGuard.Verdict.Abort) {
            tracker.transition(SyncRunPhase.ABORTED)
            SafeLog.log(SafeLog.Event.SYNC_GUARD_ABORTED, verdict.pendingDeletions)
            return buildReport(diff, listed.size, stats, GuardAbort(verdict.pendingDeletions, verdict.lastKnownTotal))
        }

        tracker.transition(SyncRunPhase.APPLYING)
        applyDiff(diff, canonicals, listed, placeholdersByUid(mappings), nowMs, stats)
        tracker.transition(SyncRunPhase.SWEEPING)
        sweepTombstones(diff, tombstones, nowMs, stats)
        finishState(listed.size, nowMs)
        tracker.transition(SyncRunPhase.COMPLETED)
        return buildReport(diff, listed.size, stats, guardAbort = null)
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
    private suspend fun fetchStage(
        listed: List<ContactMetadataDto>,
        mappings: List<ContactMapEntity>,
        writePendingIds: Set<String>,
        tombstones: List<TombstoneEntity>,
        nowMs: Long,
        stats: RunStats,
    ): List<CanonicalContact> {
        val mappingsById = mappings.associateBy { it.protonContactId }
        val tombstonedIds = tombstones.mapTo(HashSet()) { it.protonContactId }
        val canonicals = ArrayList<CanonicalContact>(listed.size)
        for (meta in listed) {
            // The push side owns a write-pending contact's convergence: the
            // three-way merge fetches the server state itself, and a pull
            // overwrite here would destroy the queued local edit.
            if (meta.id in writePendingIds) continue
            val mapping = mappingsById[meta.id]
            if (mapping != null && isSkippable(meta, mapping, tombstonedIds)) {
                db.contactMapDao().refreshBookkeeping(accountName, meta.id, mapping.modifyTime, nowMs)
                stats.unchanged++
                continue
            }
            val canonical = fetchOne(meta.id, stats) ?: continue
            canonicals += canonical
        }
        return canonicals
    }

    /** Two-tier skip: server ModifyTime didn't advance past the stored one; a tombstoned contact never skips. */
    private fun isSkippable(meta: ContactMetadataDto, mapping: ContactMapEntity, tombstonedIds: Set<String>): Boolean =
        meta.modifyTime <= mapping.modifyTime && meta.id !in tombstonedIds

    /** Null return = the contact failed loudly and was counted; never a silent drop. */
    private suspend fun fetchOne(protonContactId: String, stats: RunStats): CanonicalContact? {
        val dto = try {
            fetchContact(protonContactId)
        } catch (e: IOException) {
            // 9001 / app-version rejection gate every subsequent call — abort
            // the run by rethrowing (the SyncAdapter maps them to auth errors).
            if (e is HumanVerificationRequiredException || e is AppVersionRejectedException) throw e
            return markContactError(protonContactId, e.javaClass.simpleName, stats)
        } catch (e: IllegalArgumentException) {
            // Strict DTO parsing failing closed (Rule 5) — the API shape moved.
            return markContactError(protonContactId, e.javaClass.simpleName, stats)
        }
        stats.fetched++
        val result = decrypter.decryptContact(dto.cards)
        if (result.failures.isNotEmpty()) {
            stats.cardFailures += result.failures.size
            return markContactError(protonContactId, "card_failures", stats)
        }
        val canonical = VCardMerger.merge(protonContactId, result.cards)
        if (!canonical.verified) stats.unverifiedContacts++
        return canonical
    }

    private suspend fun markContactError(protonContactId: String, tag: String, stats: RunStats): CanonicalContact? {
        stats.contactErrors++
        // No-op when the contact was never mapped (new contact failing its
        // first fetch) — the report count is the loud part there.
        db.contactMapDao().markError(accountName, protonContactId, tag)
        return null
    }

    private suspend fun applyDiff(
        diff: ContactDiff,
        canonicals: List<CanonicalContact>,
        listed: List<ContactMetadataDto>,
        placeholdersByUid: Map<String, ContactMapEntity>,
        nowMs: Long,
        stats: RunStats,
    ) {
        val preexisting = writer.readExistingRawIds()
        val collapsed = HashMap<String, ContactMapEntity>()
        val intents = ArrayList<RawContactOpIntent>(diff.newContacts.size + diff.changedContacts.size)
        for (new in diff.newContacts) {
            val placeholder = new.projected.protonUid?.let(placeholdersByUid::get)
            when {
                // Lost-create-response collapse (ADR 0007 Section 3): the
                // server contact carries our client-generated UID — stamp the
                // existing provider row instead of writing a duplicate.
                placeholder != null -> {
                    collapsed[new.projected.protonContactId] = placeholder
                    intents += RawContactOpIntent.SetSourceId(
                        placeholder.androidRawContactId,
                        new.projected.protonContactId,
                    )
                }
                // Recovery path (ADR 0005 Section 3): the provider row already
                // exists (Room wipe / crash between apply and reconcile) — update
                // it instead of writing a duplicate.
                preexisting[new.projected.protonContactId] != null -> {
                    val recoveredId = preexisting.getValue(new.projected.protonContactId)
                    intents += RawContactOpIntent.UpdateContact(recoveredId, new.projected)
                }
                else -> intents += RawContactOpIntent.CreateContact(new.projected)
            }
        }
        for (changed in diff.changedContacts) {
            val rawId = changed.mapping.androidRawContactId
            intents += if (rawId in preexisting.values) {
                RawContactOpIntent.UpdateContact(rawId, changed.projected)
            } else {
                // The provider row vanished without us deleting it (e.g. user
                // removed the contact in a Contacts app) — recreate it.
                RawContactOpIntent.CreateContact(changed.projected)
            }
        }
        if (intents.isNotEmpty()) writer.apply(intents)
        reconcileMappings(diff, canonicals, listed, collapsed, nowMs, stats)
    }

    /** UID → placeholder mapping, for the lost-create-response collapse in [applyDiff]. */
    private fun placeholdersByUid(mappings: List<ContactMapEntity>): Map<String, ContactMapEntity> {
        val out = HashMap<String, ContactMapEntity>()
        for (mapping in mappings) {
            val uid = mapping.protonUid
            if (uid != null && LocalChangeDetector.isLocalPlaceholder(mapping.protonContactId)) {
                out[uid] = mapping
            }
        }
        return out
    }

    /** Room reconcile with post-apply provider IDs (research notes §4.2 step 7). */
    private suspend fun reconcileMappings(
        diff: ContactDiff,
        canonicals: List<CanonicalContact>,
        listed: List<ContactMetadataDto>,
        collapsed: Map<String, ContactMapEntity>,
        nowMs: Long,
        stats: RunStats,
    ) {
        val context = ReconcileContext(
            postApply = writer.readExistingRawIds(),
            canonicalsById = canonicals.associateBy { it.protonContactId },
            verifiedById = canonicals.associate { it.protonContactId to it.verified },
            modifyTimeById = listed.associate { it.id to it.modifyTime },
        )
        for (new in diff.newContacts) {
            reconcileNew(new, collapsed, context, nowMs, stats)
        }
        for (changed in diff.changedContacts) {
            // The provider map is authoritative: it covers both the plain
            // update path and the vanished-row recreate fallback above.
            val rawId = context.postApply[changed.projected.protonContactId] ?: changed.mapping.androidRawContactId
            upsertMapping(changed.projected, rawId, changed.contentHash, changed.photoHash, context, nowMs)
            context.canonicalsById[changed.projected.protonContactId]?.let { canonicalPersistence.onApplied(it) }
            stats.updated++
        }
        for (unchanged in diff.unchangedContacts) {
            val id = unchanged.mapping.protonContactId
            db.contactMapDao().refreshBookkeeping(accountName, id, context.modifyTimeById[id] ?: 0L, nowMs)
            canonicalPersistence.backfillIfMissing(unchanged.mapping, context.canonicalsById[id])
            stats.unchanged++
        }
    }

    private suspend fun reconcileNew(
        new: NewContact,
        collapsed: Map<String, ContactMapEntity>,
        context: ReconcileContext,
        nowMs: Long,
        stats: RunStats,
    ) {
        val rawId = context.postApply[new.projected.protonContactId]
        if (rawId == null) {
            markContactError(new.projected.protonContactId, "provider_write_missing", stats)
            return
        }
        collapsed[new.projected.protonContactId]?.let { placeholder ->
            // The collapse completes the create the response lost: the
            // placeholder mapping and its outbox rows are spent.
            db.withTransaction {
                db.contactMapDao().deleteByProtonId(accountName, placeholder.protonContactId)
                db.outboxDao().deleteByContact(accountName, placeholder.protonContactId)
            }
        }
        upsertMapping(new.projected, rawId, new.contentHash, new.photoHash, context, nowMs)
        context.canonicalsById[new.projected.protonContactId]?.let { canonicalPersistence.onApplied(it) }
        stats.inserted++
    }

    private suspend fun upsertMapping(
        projected: ProjectedContact,
        rawContactId: Long,
        contentHash: String,
        photoHash: String?,
        context: ReconcileContext,
        nowMs: Long,
    ) {
        val id = projected.protonContactId
        db.contactMapDao().upsert(
            ContactMapEntity(
                accountName = accountName,
                protonContactId = id,
                protonUid = projected.protonUid,
                androidRawContactId = rawContactId,
                modifyTime = context.modifyTimeById[id] ?: 0L,
                contentHash = contentHash,
                photoHash = photoHash,
                isVerified = context.verifiedById[id] ?: false,
                syncStatus = ContactMapEntity.Status.CLEAN,
                lastError = null,
                lastSyncedAt = nowMs,
            ),
        )
    }

    /** The per-run lookup maps the reconcile loops share. */
    private data class ReconcileContext(
        val postApply: Map<String, Long>,
        val canonicalsById: Map<String, CanonicalContact>,
        val verifiedById: Map<String, Boolean>,
        val modifyTimeById: Map<String, Long>,
    )

    private suspend fun sweepTombstones(
        diff: ContactDiff,
        tombstones: List<TombstoneEntity>,
        nowMs: Long,
        stats: RunStats,
    ) {
        // Restored tombstones are excluded from the sweep even when expired:
        // the contact is back on the server, so its provider row must stay.
        val restoredIds = diff.restored.mapTo(HashSet()) { it.protonContactId }
        val expired = TombstoneLifecycle.expired(tombstones, nowMs)
            .filter { it.protonContactId !in restoredIds }
        if (expired.isNotEmpty()) {
            writer.apply(expired.map { RawContactOpIntent.DeleteContact(it.protonContactId) })
            expired.forEach {
                db.contactMapDao().deleteByProtonId(accountName, it.protonContactId)
                canonicalPersistence.onRemoved(it.protonContactId)
            }
            db.tombstoneDao().deleteExpired(accountName, nowMs)
            stats.swept = expired.size
        }
        diff.restored.forEach { db.tombstoneDao().delete(accountName, it.protonContactId) }
        diff.deletedContacts.forEach { db.tombstoneDao().upsert(TombstoneLifecycle.create(it, nowMs)) }
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

    private fun buildReport(diff: ContactDiff, listed: Int, stats: RunStats, guardAbort: GuardAbort?): SyncReport =
        SyncReport(
            listed = listed,
            fetched = stats.fetched,
            inserted = stats.inserted,
            updated = stats.updated,
            unchanged = stats.unchanged,
            tombstonedNow = diff.deletedContacts.size,
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

    /** Mutable per-run counters; the report is built from it at the end. */
    private class RunStats {
        var fetched = 0
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var swept = 0
        var contactErrors = 0
        var cardFailures = 0
        var unverifiedContacts = 0
    }
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
