// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Per-entry push semantics adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/sync/.../contacts/ContactWriteEngine.kt. Deviations per ADR 0006
// (Accepted Option B) / ADR 0007: UPDATE fetches the server state and runs
// the three-way merge on the REAL stored canonical base (their empty-base
// approximation is rejected); a both-sides-same-field conflict resolves
// server-wins deterministically, the losing local vCard preserved
// Keystore-wrapped in conflict_copies with a per-field hash log; a missing
// canonical base falls back to the re-fetched server state (Section 5(ii));
// the canonical store, mapping hashes, and server payload hash advance only
// after the push lands.

package app.alpensync.contacts.sync

import androidx.room.withTransaction
import app.alpensync.contacts.store.CanonicalStoreException
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CanonicalVCardEditor
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.contacts.vcard.ContactMerger
import app.alpensync.contacts.vcard.ContactProjection
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.ProjectionReconciler
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.BulkDeleteRequest
import app.alpensync.core.api.dto.BulkDeleteResponse
import app.alpensync.core.api.dto.CreateContactsRequest
import app.alpensync.core.api.dto.CreateContactsResponse
import app.alpensync.core.api.dto.UpdateContactRequest
import app.alpensync.core.api.dto.UpdateContactResponse
import app.alpensync.core.api.dto.failedIds
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.db.entity.ConflictCopyEntity
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.OutboxEntity
import ezvcard.VCard
import java.io.IOException

/**
 * The write-side API seam (one contact per call — ADR 0007 Section 4's
 * single-item batches, so per-index/per-ID sub-responses are trivially
 * walked; HTTP 200 is never per-item success). Production wraps the Retrofit
 * calls in `mapServerCodes`, so HTTP failures arrive as
 * `ProtonServerCodeException` with a status the engine's classifier can read.
 */
interface ContactWriteApi {
    suspend fun create(request: CreateContactsRequest): CreateContactsResponse

    suspend fun update(protonContactId: String, request: UpdateContactRequest): UpdateContactResponse

    /** Genuinely PUT contacts/v4/contacts/delete; per-ID sub-responses; no server Trash. */
    suspend fun delete(request: BulkDeleteRequest): BulkDeleteResponse

    /**
     * The freshly pulled canonical server state for the merge's `theirs`, or
     * null when its cards fail to decrypt — pushing without it would be
     * blind, so null quarantines the entry.
     */
    suspend fun fetchCanonical(protonContactId: String): CanonicalContact?
}

/** The pusher's verdict for one entry; [ContactWriteEngine] applies the bookkeeping. */
sealed interface PushOutcome {
    /** The entry is fully handled (pushed, or deliberately dropped); [report] carries the counts. */
    data class Done(val report: WriteReport) : PushOutcome

    /** Retryable failure; the engine arms the quadratic backoff. [tag] is an exception simpleName, never a message. */
    data class Retry(val tag: String) : PushOutcome

    /** Permanent failure; the engine side-lines the entry for user requeue. [tag] as above. */
    data class Quarantine(val tag: String) : PushOutcome
}

/**
 * The per-op half of the outbox drain: what one CREATE / UPDATE / DELETE
 * entry does. Failures it cannot decide on (transport, HTTP) propagate to
 * [ContactWriteEngine]'s total catch; outcomes it CAN decide (sub-responses,
 * missing local state) return as [PushOutcome].
 */
class OutboxEntryPusher(
    private val accountName: String,
    stores: ContactsSyncStore,
    private val factory: ContactWriteFactory,
    private val api: ContactWriteApi,
    private val readLocal: (rawContactId: Long, protonContactId: String) -> ProjectedContact?,
    private val writer: ContactsWriterGateway,
    private val clock: () -> Long,
) {

    private val db = stores.db
    private val store = stores.canonical

    suspend fun push(entry: OutboxEntity): PushOutcome = when (entry.opType) {
        OutboxEntity.OpType.CREATE -> pushCreate(entry)
        OutboxEntity.OpType.UPDATE -> pushUpdate(entry)
        OutboxEntity.OpType.DELETE -> pushDelete(entry)
        else -> PushOutcome.Quarantine("unknown_op")
    }

    private suspend fun pushCreate(entry: OutboxEntity): PushOutcome {
        val placeholder = db.contactMapDao().findByProtonId(accountName, entry.protonContactId)
            ?: return PushOutcome.Quarantine("create_mapping_missing")
        // The provider row vanished before the push (deleted + purged
        // locally): nothing left to push — drop the entry and the placeholder.
        val local = readLocal(placeholder.androidRawContactId, entry.protonContactId)
            ?: return abandonCreate(entry)
        val uid = placeholder.protonUid ?: ContactWriteFactory.newUid()
        val plan = factory.buildCreate(local, uid)
        // A lost or incomplete create response must not POST again (research
        // notes §2.4): the server may already have the contact. Quarantine
        // and let the next pull collapse by UID.
        val item = try {
            api.create(plan.request).responses.singleOrNull()
        } catch (e: IOException) {
            return PushOutcome.Quarantine("create_maybe_landed")
        } ?: return PushOutcome.Quarantine("create_maybe_landed")
        if (item.response.code != CODE_SUCCESS) {
            SafeLog.log(SafeLog.Event.SERVER_CODE, item.response.code)
            return PushOutcome.Quarantine("create_rejected")
        }
        val created = item.response.contact ?: return PushOutcome.Quarantine("create_maybe_landed")
        return commitCreate(entry, placeholder, plan, created.id, created.uid.ifBlank { uid }, created.modifyTime)
    }

    private suspend fun commitCreate(
        entry: OutboxEntity,
        placeholder: ContactMapEntity,
        plan: ContactWriteFactory.CreatePlan,
        serverId: String,
        serverUid: String,
        serverModifyTime: Long,
    ): PushOutcome {
        // Provider re-key FIRST: a crash after it leaves the placeholder for
        // the pull's UID collapse; the reverse order could re-detect the row
        // as a fresh local create and duplicate it server-side.
        writer.apply(listOf(RawContactOpIntent.SetSourceId(placeholder.androidRawContactId, serverId)))
        val canonicalText = CanonicalVCardText.write(plan.canonicalVCard)
        val pushed = ContactProjection.project(CanonicalContact.ofVCard(serverId, plan.canonicalVCard))
        db.withTransaction {
            db.contactMapDao().deleteByProtonId(accountName, entry.protonContactId)
            db.contactMapDao().upsert(
                placeholder.copy(
                    protonContactId = serverId,
                    protonUid = serverUid,
                    modifyTime = serverModifyTime,
                    contentHash = ContactHasher.contentHash(pushed),
                    photoHash = ContactHasher.photoHash(pushed),
                    syncStatus = ContactMapEntity.Status.CLEAN,
                    lastSyncedAt = clock(),
                    lastKnownServerPayloadHash = CanonicalVCardText.payloadHash(canonicalText),
                ),
            )
            store.write(accountName, serverId, canonicalText)
            db.outboxDao().deleteByContact(accountName, entry.protonContactId)
        }
        return PushOutcome.Done(WriteReport(created = 1))
    }

    private suspend fun abandonCreate(entry: OutboxEntity): PushOutcome {
        db.withTransaction {
            db.outboxDao().deleteByContact(accountName, entry.protonContactId)
            db.contactMapDao().deleteByProtonId(accountName, entry.protonContactId)
        }
        return PushOutcome.Done(WriteReport())
    }

    private suspend fun pushUpdate(entry: OutboxEntity): PushOutcome {
        val mapping = db.contactMapDao().findByProtonId(accountName, entry.protonContactId)
            ?: return PushOutcome.Quarantine("update_mapping_missing")
        val theirs = api.fetchCanonical(entry.protonContactId)
            ?: return PushOutcome.Quarantine("server_cards_undecryptable")
        // §5(ii): the re-fetch IS the base when no store row exists.
        val base = readBase(entry.protonContactId) ?: theirs.vcard
        val local = readLocal(mapping.androidRawContactId, entry.protonContactId)
            ?: return PushOutcome.Quarantine("update_local_missing")
        val candidate = mergeCandidate(entry.protonContactId, mapping, base, local, theirs.vcard)
        val response = api.update(
            entry.protonContactId,
            UpdateContactRequest(factory.serializer.serialize(candidate.merged)),
        )
        if (response.code != CODE_SUCCESS) {
            SafeLog.log(SafeLog.Event.SERVER_CODE, response.code)
            return PushOutcome.Quarantine("update_rejected")
        }
        return commitUpdate(entry, mapping, candidate, response.contact?.modifyTime)
    }

    /** The update candidate: local edits onto the base, three-way-merged when the server moved (ADR 0006 Option B). */
    private fun mergeCandidate(
        protonContactId: String,
        mapping: ContactMapEntity,
        base: VCard,
        local: ProjectedContact,
        theirs: VCard,
    ): MergeCandidate {
        val baselineProjection = ContactProjection.project(CanonicalContact.ofVCard(protonContactId, base))
        val reconciled = ProjectionReconciler.reconcile(local, baselineProjection)
        val ours = CanonicalVCardEditor.applyEdits(base, reconciled, photoUpdate(reconciled, baselineProjection))
        val theirsHash = CanonicalVCardText.payloadHash(CanonicalVCardText.write(theirs))
        val diverged = mapping.lastKnownServerPayloadHash == null || mapping.lastKnownServerPayloadHash != theirsHash
        if (!diverged) {
            return MergeCandidate(
                ours = ours,
                merged = ours,
                mergedProjection = ContactProjection.project(CanonicalContact.ofVCard(protonContactId, ours)),
                localProjection = reconciled,
                conflicts = emptyList(),
            )
        }
        val outcome = ContactMerger.merge(protonContactId, base, ours, theirs)
        return MergeCandidate(
            ours = ours,
            merged = outcome.merged,
            mergedProjection = outcome.projection,
            localProjection = reconciled,
            conflicts = outcome.conflicts,
        )
    }

    private suspend fun commitUpdate(
        entry: OutboxEntity,
        mapping: ContactMapEntity,
        candidate: MergeCandidate,
        serverModifyTime: Long?,
    ): PushOutcome {
        // Provider apply before the store write: if apply throws, the
        // pre-push ancestor stays in the store so a retry re-merges
        // against the real base (server-wins stays server-wins).
        if (candidate.mergedProjection != candidate.localProjection) {
            writer.apply(
                listOf(RawContactOpIntent.UpdateContact(mapping.androidRawContactId, candidate.mergedProjection)),
            )
        }
        if (candidate.conflicts.isNotEmpty()) {
            recordConflicts(entry.protonContactId, candidate.ours, candidate.conflicts)
        }
        val canonicalText = CanonicalVCardText.write(candidate.merged)
        db.withTransaction {
            store.write(accountName, entry.protonContactId, canonicalText)
            db.contactMapDao().upsert(
                mapping.copy(
                    modifyTime = serverModifyTime ?: mapping.modifyTime,
                    contentHash = ContactHasher.contentHash(candidate.mergedProjection),
                    photoHash = ContactHasher.photoHash(candidate.mergedProjection),
                    syncStatus = ContactMapEntity.Status.CLEAN,
                    lastError = null,
                    lastSyncedAt = clock(),
                    lastKnownServerPayloadHash = CanonicalVCardText.payloadHash(canonicalText),
                ),
            )
            db.outboxDao().deleteById(entry.id)
        }
        return PushOutcome.Done(WriteReport(updated = 1, conflicts = candidate.conflicts.size))
    }

    private suspend fun pushDelete(entry: OutboxEntity): PushOutcome {
        if (entry.createdAt + OutboxEntity.GRACE_PERIOD_MS > clock()) {
            return PushOutcome.Done(WriteReport(skippedGrace = 1))
        }
        if (db.contactMapDao().findByProtonId(accountName, entry.protonContactId) == null) {
            // The pull-side sweep already confirmed the delete (or the row is
            // otherwise gone): the intent is fulfilled without another call.
            db.outboxDao().deleteById(entry.id)
            return PushOutcome.Done(WriteReport(deleted = 1))
        }
        val response = api.delete(BulkDeleteRequest(listOf(entry.protonContactId)))
        if (entry.protonContactId in response.failedIds()) {
            // A per-ID sub-response failure keeps ONLY this entry queued —
            // each delete is its own call, so siblings are never collateral.
            val code = response.responses.firstOrNull { it.id == entry.protonContactId }?.response?.code ?: 0
            SafeLog.log(SafeLog.Event.SYNC_OUTBOX_DELETE_SUBCODE_FAILED, code)
            return PushOutcome.Retry("delete_subcode")
        }
        db.withTransaction {
            db.contactMapDao().deleteByProtonId(accountName, entry.protonContactId)
            store.delete(accountName, entry.protonContactId)
            db.outboxDao().deleteById(entry.id)
        }
        purgeProviderRow(entry.protonContactId)
        return PushOutcome.Done(WriteReport(deleted = 1))
    }

    /** Purge the provider's DELETED=1 row so later scans never re-detect it. Best-effort: the detector re-purges. */
    private fun purgeProviderRow(protonContactId: String) {
        try {
            writer.apply(listOf(RawContactOpIntent.DeleteContact(protonContactId)))
        } catch (ignored: IOException) {
            SafeLog.log(SafeLog.Event.SYNC_PROVIDER_ROW_PURGE_FAILED)
        }
    }

    /**
     * The losing side of every both-sides conflict (ADR 0006's never-silent
     * rule): the full local vCard, Keystore-wrapped, plus the per-field hash
     * log the M4 sync-log viewer renders. No plaintext ever lands in the row.
     */
    private suspend fun recordConflicts(
        protonContactId: String,
        ours: VCard,
        conflicts: List<ContactMerger.FieldConflict>,
    ) {
        db.conflictCopyDao().insert(
            ConflictCopyEntity(
                accountName = accountName,
                protonContactId = protonContactId,
                detectedAt = clock(),
                resolution = ConflictCopyEntity.Resolution.SERVER_WON,
                losingSide = ConflictCopyEntity.LosingSide.LOCAL,
                payloadEnc = store.encryptPayload(CanonicalVCardText.write(ours).toByteArray(Charsets.UTF_8)),
                fieldConflictsJson = ConflictLogCodec.encode(conflicts),
            ),
        )
        SafeLog.log(SafeLog.Event.SYNC_CONFLICT_SERVER_WON, conflicts.size)
    }

    /**
     * The stored canonical base, or null (missing/undecryptable/unparseable)
     * — the caller re-bases on the server state.
     */
    private suspend fun readBase(protonContactId: String): VCard? {
        val text = try {
            store.read(accountName, protonContactId)
        } catch (ignored: CanonicalStoreException) {
            return null // logged at the store boundary
        } ?: return null
        return try {
            CanonicalVCardText.parse(text)
        } catch (ignored: IllegalArgumentException) {
            SafeLog.log(SafeLog.Event.CANONICAL_STORE_PARSE_FAILED)
            null
        }
    }

    /**
     * Photo unchanged → keep Proton's original bytes; only a real local
     * change pushes provider bytes (§3 photo rule).
     */
    private fun photoUpdate(
        local: ProjectedContact,
        baseline: ProjectedContact,
    ): CanonicalVCardEditor.PhotoUpdate = when {
        ContactHasher.photoHash(local) == ContactHasher.photoHash(baseline) ->
            CanonicalVCardEditor.PhotoUpdate.KEEP_SERVER_BYTES
        local.photo == null -> CanonicalVCardEditor.PhotoUpdate.REMOVE
        else -> CanonicalVCardEditor.PhotoUpdate.REPLACE_FROM_PROJECTION
    }

    /** The merge/update handoff: the candidate payload plus everything the commit step needs. */
    private data class MergeCandidate(
        val ours: VCard,
        val merged: VCard,
        val mergedProjection: ProjectedContact,
        val localProjection: ProjectedContact,
        val conflicts: List<ContactMerger.FieldConflict>,
    )

    private companion object {
        /** Proton's success `Code` — sub-responses are the only error channel of the bulk endpoints. */
        const val CODE_SUCCESS = 1000
    }
}
