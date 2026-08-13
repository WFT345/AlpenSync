// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Enqueue policy adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/sync/.../contacts/ContactWriteEngine.kt (detectChanges half).
// Deviations: the hash gate reconciles the provider read-back against the
// stored canonical baseline first (ProjectionReconciler), so the platform's
// lossy echo never produces a false-positive UPDATE; creates get a
// client-generated UID + a placeholder contact_map row at enqueue time so a
// lost create response collapses by proton_uid on the next pull (ADR 0007
// Section 3); enqueue + markPendingPush are transactional (a crash between
// them must not strand a contact in a pull-skipped state with no outbox row).

package app.alpensync.contacts.sync

import androidx.room.withTransaction
import app.alpensync.contacts.store.CanonicalStoreException
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.contacts.vcard.ContactProjection
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.DirtyContact
import app.alpensync.contacts.writer.ProjectionReconciler
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.OutboxEntity
import ezvcard.VCard
import java.io.IOException

/**
 * The M3b dirty-detection orchestrator (ADR 0007 Section 2 — hybrid: the
 * platform DIRTY/DELETED flag is the cheap gate, the content hash against the
 * stored baseline is the truth). One [scan] per sync run, BEFORE the outbox
 * drain and the pull (push-before-pull, ADR 0007 Section 1).
 *
 * Per flagged RawContact:
 *  - DELETED, never synced (SOURCE_ID null): cancel its pending CREATE — the
 *    server never saw it, so there is nothing to delete remotely.
 *  - DELETED, synced: enqueue DELETE (deduped), cancelling any pending UPDATE.
 *  - SOURCE_ID null: CREATE — the placeholder `local-<rawId>` row captures a
 *    client-generated `urn:uuid:` UID at ENQUEUE time (stable across retries,
 *    which is what makes a lost create response dedupable by `proton_uid`).
 *  - else UPDATE: a pending DELETE is cancelled (re-edit during grace), the
 *    read-back is reconciled against the stored canonical baseline, and only
 *    a real hash difference enqueues/coalesces an UPDATE (at most one live
 *    UPDATE row per contact). A reverted edit (hash equal again) DROPS the
 *    pending UPDATE — nothing is pushed for a change that no longer exists.
 *
 * The DIRTY flag is cleared once the outbox has captured the change (or the
 * hash gate proved the flag a lie); a contact we could not read keeps its
 * flag so the next run retries. Provider failures surface as [IOException]
 * (the single failure type of the writer package) and abort the run — a
 * provider that cannot answer queries cannot be scanned partially.
 */
class LocalChangeDetector(
    private val accountName: String,
    stores: ContactsSyncStore,
    private val writer: ContactsWriterGateway,
    private val readDirty: () -> List<DirtyContact>,
    private val readLocal: (rawContactId: Long, protonContactId: String) -> ProjectedContact?,
    private val clearDirty: (rawContactId: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val db = stores.db
    private val store = stores.canonical

    /** Returns how many outbox rows this scan enqueued (coalesces included). */
    suspend fun scan(): Int {
        var enqueued = 0
        for (contact in readDirty()) {
            if (process(contact)) enqueued++
        }
        if (enqueued > 0) {
            SafeLog.log(SafeLog.Event.SYNC_OUTBOX_ENQUEUED, enqueued)
        }
        return enqueued
    }

    /** True when a live outbox row exists for the contact afterwards. The flag clears unless the row was unreadable. */
    private suspend fun process(contact: DirtyContact): Boolean {
        val outcome = when {
            contact.isDeleted && contact.sourceId == null -> {
                cancelPendingCreate(contact.rawContactId)
                EnqueueOutcome.SKIPPED
            }
            contact.isDeleted -> enqueueDelete(contact)
            contact.sourceId == null -> enqueueCreate(contact.rawContactId)
            else -> enqueueUpdate(contact)
        }
        if (outcome != EnqueueOutcome.UNREADABLE) {
            clearDirty(contact.rawContactId)
        }
        return outcome == EnqueueOutcome.ENQUEUED
    }

    private suspend fun cancelPendingCreate(rawContactId: Long) {
        val placeholder = placeholderId(rawContactId)
        db.withTransaction {
            db.outboxDao().deleteByContact(accountName, placeholder)
            db.contactMapDao().deleteByProtonId(accountName, placeholder)
        }
    }

    private suspend fun enqueueDelete(contact: DirtyContact): EnqueueOutcome {
        val protonContactId = checkNotNull(contact.sourceId) // non-null in the caller's branch
        if (db.contactMapDao().findByProtonId(accountName, protonContactId) == null) {
            // A deleted row we hold no mapping for is a zombie (post-wipe, or
            // a post-push purge the provider never applied): purge it and
            // never enqueue — with no mapping there is no server-side intent.
            return purgeZombie(protonContactId)
        }
        var inserted = false
        db.withTransaction {
            val live = db.outboxDao().findByContact(accountName, protonContactId).filter { !it.quarantined }
            live.filter { it.opType == OutboxEntity.OpType.UPDATE }.forEach { db.outboxDao().deleteById(it.id) }
            if (live.none { it.opType == OutboxEntity.OpType.DELETE }) {
                insertOutbox(protonContactId, OutboxEntity.OpType.DELETE, payloadHash = "")
                inserted = true
            }
            db.contactMapDao().markPendingPush(accountName, protonContactId)
        }
        return if (inserted) EnqueueOutcome.ENQUEUED else EnqueueOutcome.SKIPPED
    }

    /** The zombie purge is best-effort: a provider failure leaves the flag set so the next run retries. */
    private fun purgeZombie(protonContactId: String): EnqueueOutcome = try {
        writer.apply(listOf(RawContactOpIntent.DeleteContact(protonContactId)))
        EnqueueOutcome.SKIPPED
    } catch (ignored: IOException) {
        EnqueueOutcome.UNREADABLE
    }

    private suspend fun enqueueCreate(rawContactId: Long): EnqueueOutcome {
        val placeholder = placeholderId(rawContactId)
        val live = db.outboxDao().findByContact(accountName, placeholder)
        if (live.any { !it.quarantined && it.opType == OutboxEntity.OpType.CREATE }) {
            // Re-edit of a pending create: the push rebuilds the payload from
            // the provider, so only the dirty flag needs clearing.
            return EnqueueOutcome.SKIPPED
        }
        val local = readLocal(rawContactId, placeholder) ?: return EnqueueOutcome.UNREADABLE
        db.withTransaction {
            db.contactMapDao().upsert(placeholderMapping(placeholder, rawContactId, local))
            insertOutbox(placeholder, OutboxEntity.OpType.CREATE, combinedHash(local))
        }
        return EnqueueOutcome.ENQUEUED
    }

    private suspend fun enqueueUpdate(contact: DirtyContact): EnqueueOutcome {
        val protonContactId = checkNotNull(contact.sourceId)
        // A SOURCE_ID without a mapping means the pull has not re-mapped the
        // row yet (post-wipe recovery): keep the flag, retry after the pull.
        val mapping = db.contactMapDao().findByProtonId(accountName, protonContactId)
            ?: return EnqueueOutcome.UNREADABLE
        val local = readLocal(contact.rawContactId, protonContactId) ?: return EnqueueOutcome.UNREADABLE
        val baseline = baselineProjection(protonContactId)
        val reconciled = ProjectionReconciler.reconcile(local, baseline ?: emptyProjection(protonContactId))
        val hashEqual = ContactHasher.contentHash(reconciled) == mapping.contentHash &&
            ContactHasher.photoHash(reconciled) == mapping.photoHash
        val liveUpdate = cancelDeletesAndFindUpdate(protonContactId)

        return when {
            // False-positive flag, reverted edit, or a delete cancel that
            // restored the last-synced state: nothing left to push.
            hashEqual -> {
                db.withTransaction {
                    liveUpdate?.let { db.outboxDao().deleteById(it.id) }
                    db.contactMapDao().markClean(accountName, protonContactId)
                }
                EnqueueOutcome.SKIPPED
            }
            liveUpdate != null -> {
                db.outboxDao().updatePayloadHash(liveUpdate.id, combinedHash(reconciled))
                EnqueueOutcome.ENQUEUED
            }
            else -> {
                db.withTransaction {
                    insertOutbox(protonContactId, OutboxEntity.OpType.UPDATE, combinedHash(reconciled))
                    db.contactMapDao().markPendingPush(accountName, protonContactId)
                }
                EnqueueOutcome.ENQUEUED
            }
        }
    }

    /** Re-edit cancels a pending delete (ADR 0007 Section 2); returns the live UPDATE row, if any. */
    private suspend fun cancelDeletesAndFindUpdate(protonContactId: String): OutboxEntity? {
        val live = db.outboxDao().findByContact(accountName, protonContactId).filter { !it.quarantined }
        live.filter { it.opType == OutboxEntity.OpType.DELETE }
            .forEach { db.outboxDao().deleteById(it.id) }
        return live.singleOrNull { it.opType == OutboxEntity.OpType.UPDATE }
    }

    private suspend fun insertOutbox(protonContactId: String, opType: Int, payloadHash: String) {
        db.outboxDao().insert(
            OutboxEntity(
                accountName = accountName,
                protonContactId = protonContactId,
                opType = opType,
                payloadHash = payloadHash,
                createdAt = clock(),
            ),
        )
    }

    private fun placeholderMapping(placeholder: String, rawContactId: Long, local: ProjectedContact) =
        ContactMapEntity(
            accountName = accountName,
            protonContactId = placeholder,
            protonUid = ContactWriteFactory.newUid(),
            androidRawContactId = rawContactId,
            modifyTime = 0L,
            contentHash = ContactHasher.contentHash(local),
            photoHash = ContactHasher.photoHash(local),
            isVerified = true,
            syncStatus = ContactMapEntity.Status.PENDING_PUSH,
            lastError = null,
            lastSyncedAt = clock(),
        )

    /**
     * The stored canonical baseline as a projection, or null when the row is
     * missing/undecryptable/unparseable — the reconcile then degrades to the
     * raw provider read-back (a false-positive UPDATE is wasteful, never
     * lossy; the push side re-bases on the server state).
     */
    private suspend fun baselineProjection(protonContactId: String): ProjectedContact? {
        val text = try {
            store.read(accountName, protonContactId)
        } catch (ignored: CanonicalStoreException) {
            return null // logged at the store boundary; the push path re-bases
        } ?: return null
        return ContactProjection.project(CanonicalContact.ofVCard(protonContactId, parseOrNull(text) ?: return null))
    }

    private fun parseOrNull(text: String): VCard? = try {
        CanonicalVCardText.parse(text)
    } catch (ignored: IllegalArgumentException) {
        SafeLog.log(SafeLog.Event.CANONICAL_STORE_PARSE_FAILED)
        null
    }

    private enum class EnqueueOutcome { ENQUEUED, SKIPPED, UNREADABLE }

    companion object {
        /** Placeholder `proton_contact_id` prefix for locally-created contacts until the server assigns a real ID. */
        const val LOCAL_ID_PREFIX = "local-"

        fun placeholderId(rawContactId: Long): String = "$LOCAL_ID_PREFIX$rawContactId"

        fun isLocalPlaceholder(protonContactId: String): Boolean = protonContactId.startsWith(LOCAL_ID_PREFIX)

        /** The reconcile baseline for a contact with no stored canonical (creates, pre-M3b rows). */
        fun emptyProjection(protonContactId: String): ProjectedContact = ProjectedContact(
            protonContactId = protonContactId,
            protonUid = null,
            displayName = null,
            structuredName = null,
            emails = emptyList(),
            phones = emptyList(),
            addresses = emptyList(),
            organization = null,
            notes = emptyList(),
            imAccounts = emptyList(),
            photo = null,
            urls = emptyList(),
            birthday = null,
            anniversary = null,
        )

        internal fun combinedHash(contact: ProjectedContact): String =
            ContactHasher.contentHash(contact) + "/" + (ContactHasher.photoHash(contact) ?: "-")
    }
}
