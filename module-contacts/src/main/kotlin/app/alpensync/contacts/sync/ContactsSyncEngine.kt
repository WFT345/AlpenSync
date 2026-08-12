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
 */
class ContactsSyncEngine(
    private val accountName: String,
    private val listMetadata: suspend () -> List<ContactMetadataDto>,
    private val fetchContact: suspend (String) -> ContactDto,
    private val decrypter: ContactDecrypter,
    private val writer: ContactsWriterGateway,
    private val db: AlpenSyncDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Phase observability for tests and the future sync-log viewer. */
    val tracker = SyncRunTracker()

    suspend fun run(): SyncReport {
        tracker.transition(SyncRunPhase.LISTING)
        val listed = listMetadata()
        tracker.transition(SyncRunPhase.DIFFING)
        val nowMs = clock()
        val mappings = db.contactMapDao().listForAccount(accountName)
        val tombstones = db.tombstoneDao().listForAccount(accountName)
        val lastKnownTotal = db.syncStateDao().get(accountName)?.lastKnownTotal ?: 0
        val stats = RunStats()
        val canonicals = fetchStage(listed, mappings, tombstones, nowMs, stats)
        val diff = ContactDiffer.diff(canonicals, mappings, tombstones, listed.mapTo(HashSet()) { it.id })

        tracker.transition(SyncRunPhase.GUARD_CHECK)
        val verdict = MassDeleteGuard.check(diff, lastKnownTotal)
        if (verdict is MassDeleteGuard.Verdict.Abort) {
            tracker.transition(SyncRunPhase.ABORTED)
            SafeLog.log(SafeLog.Event.SYNC_GUARD_ABORTED, verdict.pendingDeletions)
            return buildReport(diff, listed.size, stats, GuardAbort(verdict.pendingDeletions, verdict.lastKnownTotal))
        }

        tracker.transition(SyncRunPhase.APPLYING)
        applyDiff(diff, canonicals, listed, nowMs, stats)
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
        tombstones: List<TombstoneEntity>,
        nowMs: Long,
        stats: RunStats,
    ): List<CanonicalContact> {
        val mappingsById = mappings.associateBy { it.protonContactId }
        val tombstonedIds = tombstones.mapTo(HashSet()) { it.protonContactId }
        val canonicals = ArrayList<CanonicalContact>(listed.size)
        for (meta in listed) {
            val mapping = mappingsById[meta.id]
            if (mapping != null && meta.modifyTime <= mapping.modifyTime && meta.id !in tombstonedIds) {
                db.contactMapDao().refreshBookkeeping(accountName, meta.id, mapping.modifyTime, nowMs)
                stats.unchanged++
                continue
            }
            val canonical = fetchOne(meta.id, stats) ?: continue
            canonicals += canonical
        }
        return canonicals
    }

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
        nowMs: Long,
        stats: RunStats,
    ) {
        val preexisting = writer.readExistingRawIds()
        val intents = ArrayList<RawContactOpIntent>(diff.newContacts.size + diff.changedContacts.size)
        for (new in diff.newContacts) {
            // Recovery path (ADR 0005 Section 3): the provider row already
            // exists (Room wipe / crash between apply and reconcile) — update
            // it instead of writing a duplicate.
            val recoveredId = preexisting[new.projected.protonContactId]
            intents += if (recoveredId != null) {
                RawContactOpIntent.UpdateContact(recoveredId, new.projected)
            } else {
                RawContactOpIntent.CreateContact(new.projected)
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
        reconcileMappings(diff, canonicals, listed, nowMs, stats)
    }

    /** Room reconcile with post-apply provider IDs (research notes §4.2 step 7). */
    private suspend fun reconcileMappings(
        diff: ContactDiff,
        canonicals: List<CanonicalContact>,
        listed: List<ContactMetadataDto>,
        nowMs: Long,
        stats: RunStats,
    ) {
        val postApply = writer.readExistingRawIds()
        val verifiedById = canonicals.associate { it.protonContactId to it.verified }
        val modifyTimeById = listed.associate { it.id to it.modifyTime }
        for (new in diff.newContacts) {
            val rawId = postApply[new.projected.protonContactId]
            if (rawId == null) {
                markContactError(new.projected.protonContactId, "provider_write_missing", stats)
                continue
            }
            upsertMapping(new.projected, rawId, new.contentHash, new.photoHash, verifiedById, modifyTimeById, nowMs)
            stats.inserted++
        }
        for (changed in diff.changedContacts) {
            // The provider map is authoritative: it covers both the plain
            // update path and the vanished-row recreate fallback above.
            val rawId = postApply[changed.projected.protonContactId] ?: changed.mapping.androidRawContactId
            upsertMapping(
                changed.projected,
                rawId,
                changed.contentHash,
                changed.photoHash,
                verifiedById,
                modifyTimeById,
                nowMs,
            )
            stats.updated++
        }
        for (unchanged in diff.unchangedContacts) {
            val id = unchanged.mapping.protonContactId
            db.contactMapDao().refreshBookkeeping(accountName, id, modifyTimeById[id] ?: 0L, nowMs)
            stats.unchanged++
        }
    }

    private suspend fun upsertMapping(
        projected: ProjectedContact,
        rawContactId: Long,
        contentHash: String,
        photoHash: String?,
        verifiedById: Map<String, Boolean>,
        modifyTimeById: Map<String, Long>,
        nowMs: Long,
    ) {
        val id = projected.protonContactId
        db.contactMapDao().upsert(
            ContactMapEntity(
                accountName = accountName,
                protonContactId = id,
                protonUid = projected.protonUid,
                androidRawContactId = rawContactId,
                modifyTime = modifyTimeById[id] ?: 0L,
                contentHash = contentHash,
                photoHash = photoHash,
                isVerified = verifiedById[id] ?: false,
                syncStatus = ContactMapEntity.Status.CLEAN,
                lastError = null,
                lastSyncedAt = nowMs,
            ),
        )
    }

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
            expired.forEach { db.contactMapDao().deleteByProtonId(accountName, it.protonContactId) }
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
