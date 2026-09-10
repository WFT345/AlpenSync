// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.withTransaction
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.writer.ApplyResult
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.SyncErrorEntity
import app.alpensync.core.db.entity.TombstoneEntity

/**
 * Provider write + Room reconcile for one pull apply (one contact or a
 * batch). The engine calls this after each fetch so a killed run keeps what
 * already landed. Deletes stay out of [apply] — [sweep] runs only after the
 * mass-delete guard.
 *
 * Provider IDs come from [PullSnapshot.knownRawIds] (seeded once per run)
 * plus [ApplyResult.createdRawIds] after each write — no per-contact
 * provider re-reads.
 */
internal class ContactApplyStage(
    private val accountName: String,
    private val db: AlpenSyncDatabase,
    private val writer: ContactsWriterGateway,
    private val canonicalPersistence: CanonicalPersistence,
) {

    fun placeholdersByUid(mappings: List<ContactMapEntity>): Map<String, ContactMapEntity> {
        val out = HashMap<String, ContactMapEntity>()
        for (mapping in mappings) {
            val uid = mapping.protonUid
            if (uid != null && LocalChangeDetector.isLocalPlaceholder(mapping.protonContactId)) {
                out[uid] = mapping
            }
        }
        return out
    }

    suspend fun apply(
        diff: ContactDiff,
        canonicals: List<CanonicalContact>,
        snap: PullSnapshot,
        nowMs: Long,
        stats: PullRunStats,
    ) {
        val knownRawIds = snap.knownRawIds
        val collapsed = HashMap<String, ContactMapEntity>()
        val intents = ArrayList<RawContactOpIntent>(diff.newContacts.size + diff.changedContacts.size)
        for (new in diff.newContacts) {
            val placeholder = new.projected.protonUid?.let(snap.placeholders::get)
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
                knownRawIds[new.projected.protonContactId] != null -> {
                    val recoveredId = knownRawIds.getValue(new.projected.protonContactId)
                    intents += RawContactOpIntent.UpdateContact(recoveredId, new.projected)
                }
                else -> intents += RawContactOpIntent.CreateContact(new.projected)
            }
        }
        for (changed in diff.changedContacts) {
            val rawId = changed.mapping.androidRawContactId
            intents += if (rawId in knownRawIds.values) {
                RawContactOpIntent.UpdateContact(rawId, changed.projected)
            } else {
                // The provider row vanished without us deleting it (e.g. user
                // removed the contact in a Contacts app) — recreate it.
                RawContactOpIntent.CreateContact(changed.projected)
            }
        }
        if (intents.isNotEmpty()) recordApplied(intents, writer.apply(intents), knownRawIds)
        reconcileMappings(diff, canonicals, snap, collapsed, nowMs, stats)
    }

    suspend fun markError(protonContactId: String, tag: String, stats: PullRunStats, nowMs: Long) {
        stats.contactErrors++
        // contact_map.markError is an UPDATE: for a contact with no mapping
        // row yet it affects zero rows. sync_errors is the durable record —
        // a first-pull failure must survive the run, not just its counter.
        db.contactMapDao().markError(accountName, protonContactId, tag)
        db.syncErrorDao().upsert(SyncErrorEntity(accountName, protonContactId, tag, nowMs))
    }

    suspend fun sweep(diff: ContactDiff, tombstones: List<TombstoneEntity>, nowMs: Long, stats: PullRunStats) {
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

    /** Fold the applied intents into the run's provider-ID map so the next contact sees them. */
    private fun recordApplied(
        intents: List<RawContactOpIntent>,
        result: ApplyResult,
        knownRawIds: MutableMap<String, Long>,
    ) {
        for (intent in intents) {
            when (intent) {
                is RawContactOpIntent.CreateContact ->
                    result.createdRawIds[intent.projected.protonContactId]
                        ?.let { knownRawIds[intent.projected.protonContactId] = it }
                is RawContactOpIntent.SetSourceId -> knownRawIds[intent.sourceId] = intent.rawContactId
                is RawContactOpIntent.DeleteContact -> knownRawIds.remove(intent.sourceId)
                is RawContactOpIntent.UpdateContact -> Unit // raw row keeps its ID
            }
        }
    }

    private suspend fun reconcileMappings(
        diff: ContactDiff,
        canonicals: List<CanonicalContact>,
        snap: PullSnapshot,
        collapsed: Map<String, ContactMapEntity>,
        nowMs: Long,
        stats: PullRunStats,
    ) {
        val context = ReconcileContext(
            postApply = snap.knownRawIds,
            canonicalsById = canonicals.associateBy { it.protonContactId },
            verifiedById = canonicals.associate { it.protonContactId to it.verified },
            modifyTimeById = snap.modifyTimeById,
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
        stats: PullRunStats,
    ) {
        val rawId = context.postApply[new.projected.protonContactId]
        if (rawId == null) {
            markError(new.projected.protonContactId, "provider_write_missing", stats, nowMs)
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
        db.syncErrorDao().clear(accountName, id) // any earlier failure is spent
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

    private data class ReconcileContext(
        val postApply: Map<String, Long>,
        val canonicalsById: Map<String, CanonicalContact>,
        val verifiedById: Map<String, Boolean>,
        val modifyTimeById: Map<String, Long>,
    )
}
