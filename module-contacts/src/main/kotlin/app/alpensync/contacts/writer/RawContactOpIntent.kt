// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../RawContactOpIntent.kt. Deviation: the row
// payload is the vcard layer's ProjectedContact directly — that projection IS
// the row model here, so pcontacts' intermediate ContactRow mirror class is
// not duplicated (Rule 14; the vcard layer still never sees ContactsContract).

package app.alpensync.contacts.writer

import app.alpensync.contacts.vcard.ProjectedContact

/**
 * Pure-data description of what the differ wants the writer to do. Separates
 * "decide what changed" (sync/ContactDiffer — pure JVM, unit-tested) from
 * "build the right ContentProviderOperation list" (ContactsContractOps —
 * Android-tied, Robolectric-tested).
 */
sealed interface RawContactOpIntent {

    /** Server has a contact we've never written. Insert a fresh RawContact + Data rows. */
    data class CreateContact(val projected: ProjectedContact) : RawContactOpIntent

    /**
     * Server's row matches an existing RawContact (by SOURCE_ID). Per
     * ADR 0005 Section 6: delete child Data rows + reinsert, keep the
     * RawContacts._ID stable (preserves user-owned aggregate state:
     * starred, ringtone).
     */
    data class UpdateContact(val rawContactId: Long, val projected: ProjectedContact) : RawContactOpIntent

    /** A tombstone's grace period expired — delete the whole RawContact. */
    data class DeleteContact(val sourceId: String) : RawContactOpIntent

    /**
     * M3b: a locally-created RawContact (SOURCE_ID null) just got its server
     * ID from a successful create push — stamp it so the next pull recognizes
     * the row as already-synced instead of writing a duplicate (the ADR 0005
     * Section 3 recovery path keys off SOURCE_ID).
     */
    data class SetSourceId(val rawContactId: Long, val sourceId: String) : RawContactOpIntent
}
