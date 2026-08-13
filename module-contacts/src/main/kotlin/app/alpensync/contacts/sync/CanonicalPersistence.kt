// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity

/**
 * The pull side's half of the canonical-store contract (ADR 0007 Section
 * 5(i)): every contact the pull engine applies also persists its canonical
 * vCard Keystore-wrapped and records the server payload hash, so the write
 * path has a real merge base and a divergence hint (ADR 0006 Option B).
 *
 * [backfillIfMissing] covers contacts last applied before M3b (or after a
 * store wipe): the first pull that re-fetches them repairs the row — the
 * write path's §5(ii) re-fetch fallback covers the window until then.
 */
internal class CanonicalPersistence(
    private val accountName: String,
    private val db: AlpenSyncDatabase,
    private val store: CanonicalVCardStore,
) {

    /** Persist after a successful apply: store row + `last_known_server_payload_hash` advance together. */
    suspend fun onApplied(contact: CanonicalContact) {
        val text = CanonicalVCardText.write(contact.vcard)
        store.write(accountName, contact.protonContactId, text)
        db.contactMapDao().updateServerPayloadHash(
            accountName,
            contact.protonContactId,
            CanonicalVCardText.payloadHash(text),
        )
    }

    /** M2-era rows have no store row and a NULL payload hash; a re-fetched but hash-equal contact repairs both. */
    suspend fun backfillIfMissing(mapping: ContactMapEntity, canonical: CanonicalContact?) {
        val id = mapping.protonContactId
        if (mapping.lastKnownServerPayloadHash != null && store.exists(accountName, id)) return
        val text = canonical?.let { CanonicalVCardText.write(it.vcard) } ?: return
        if (!store.exists(accountName, id)) {
            store.write(accountName, id, text)
        }
        if (mapping.lastKnownServerPayloadHash == null) {
            db.contactMapDao().updateServerPayloadHash(accountName, id, CanonicalVCardText.payloadHash(text))
        }
    }

    /** The contact is gone (tombstone sweep): store row AND any pending outbox rows are moot. */
    suspend fun onRemoved(protonContactId: String) {
        store.delete(accountName, protonContactId)
        db.outboxDao().deleteByContact(accountName, protonContactId)
    }
}
