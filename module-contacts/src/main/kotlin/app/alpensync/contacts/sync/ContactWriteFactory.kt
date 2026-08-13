// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.CanonicalVCardEditor
import app.alpensync.contacts.vcard.CanonicalVCardEditor.PhotoUpdate
import app.alpensync.contacts.vcard.ContactSerializer
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.core.api.dto.BulkDeleteRequest
import app.alpensync.core.api.dto.ContactCardBundle
import app.alpensync.core.api.dto.CreateContactsRequest
import app.alpensync.core.api.dto.UpdateContactRequest
import ezvcard.VCard
import ezvcard.property.Uid
import java.util.UUID

/**
 * Assembles the three write-path API requests (ADR 0007 Sections 3-4) from
 * the editor + serializer. Pure JVM, no Android imports.
 *
 * - CREATE: the local projection becomes the whole canonical vCard (nothing
 *   pre-exists to preserve) and carries a **client-generated `urn:uuid:`
 *   UID** — unlike pcontacts (server-assigned), so a create whose response
 *   was lost collapses as a duplicate by `proton_uid` on the next pull
 *   (research notes Sections 1.3 + 2.4; hydroxide shows servers honor
 *   client UIDs). [CreatePlan.assignedUid] is what the M3b engine stores for
 *   that dedup.
 * - UPDATE: the edit is applied onto the stored canonical vCard (never the
 *   projection alone) and the existing vCard UID rides in the SIGNED card —
 *   losslessness is mandatory because PUT replaces the whole Cards[] array.
 * - DELETE: one ID per call (ADR 0007 Section 4), no server Trash — the
 *   outbox grace is the only undo.
 *
 * The returned plans carry the updated canonical vCard so the engine can
 * persist it (encrypted canonical store) after a successful push.
 */
class ContactWriteFactory(
    val serializer: ContactSerializer,
) {

    data class CreatePlan(
        val request: CreateContactsRequest,
        val assignedUid: String,
        val canonicalVCard: VCard,
    )

    data class UpdatePlan(
        val request: UpdateContactRequest,
        val canonicalVCard: VCard,
    )

    /**
     * [uid] defaults to a fresh one; the write engine passes the UID captured
     * at ENQUEUE time (placeholder mapping row) so a retried create keeps a
     * stable identity — the lost-response dedup hinges on it (ADR 0007 §3).
     */
    fun buildCreate(edited: ProjectedContact, uid: String = newUid()): CreatePlan {
        val canonical = CanonicalVCardEditor.applyEdits(VCard(), edited, PhotoUpdate.REPLACE_FROM_PROJECTION)
        canonical.uid = Uid(uid)
        val cards = serializer.serialize(canonical)
        return CreatePlan(
            request = CreateContactsRequest(listOf(ContactCardBundle(cards))),
            assignedUid = uid,
            canonicalVCard = canonical,
        )
    }

    fun buildUpdate(
        canonical: VCard,
        edited: ProjectedContact,
        photoUpdate: PhotoUpdate,
    ): UpdatePlan {
        val updated = CanonicalVCardEditor.applyEdits(canonical, edited, photoUpdate)
        return UpdatePlan(
            request = UpdateContactRequest(serializer.serialize(updated)),
            canonicalVCard = updated,
        )
    }

    fun buildDelete(protonContactId: String): BulkDeleteRequest = BulkDeleteRequest(listOf(protonContactId))

    companion object {
        /** `urn:uuid:<UUID>` — the vCard 4.0 UID form (research notes Section 1.3). */
        fun newUid(): String = "urn:uuid:${UUID.randomUUID()}"
    }
}
