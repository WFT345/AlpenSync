// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../ContactsContractOps.kt (assembly half).
// Deviations: child rows come from the single ContactDataOps set via the
// ParentRef seam (no duplicated builder pairs); the "Send via Proton Mail"
// chip rows and GroupMembership rows are NOT written (ADR 0005 Section 5 —
// no custom MIME types at M2; groups need the group_map table, a recorded
// DB v2 follow-up). URLs land in Website rows (M2 write-set).

package app.alpensync.contacts.writer

import android.accounts.Account
import android.content.ContentProviderOperation
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import app.alpensync.contacts.vcard.ProjectedContact

/**
 * Mechanical mapping from [RawContactOpIntent] → ContentProviderOperation
 * batches, applying ADR 0005 Section 6:
 *   - every URI passes through SyncAdapterUri.decorate
 *   - Update = delete child Data rows + re-insert under a STABLE
 *     RawContacts._ID (the RawContact itself is never deleted on update —
 *     that preserves user-owned aggregate state like starred / ringtone)
 *   - Create's Data rows use withValueBackReference with ABSOLUTE batch
 *     indices ([baseIdx]); BatchPlanner re-anchors at chunk boundaries —
 *     get it wrong and Data rows attach to the wrong RawContact, silently.
 */
object ContactsContractOps {

    fun build(account: Account, intent: RawContactOpIntent, baseIdx: Int = 0): List<ContentProviderOperation> =
        when (intent) {
            is RawContactOpIntent.CreateContact -> createContactOps(account, intent.projected, baseIdx)
            is RawContactOpIntent.UpdateContact -> updateContactOps(account, intent.rawContactId, intent.projected)
            is RawContactOpIntent.DeleteContact -> listOf(deleteContactOp(account, intent.sourceId))
        }

    private fun createContactOps(
        account: Account,
        contact: ProjectedContact,
        baseIdx: Int,
    ): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type),
        )
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(RawContacts.SOURCE_ID, contact.protonContactId)
            .build()
        val dataUri = SyncAdapterUri.decorate(Data.CONTENT_URI, account.name, account.type)
        appendChildOps(ops, dataUri, ParentRef.BackReference(baseIdx), contact)
        return ops
    }

    private fun updateContactOps(
        account: Account,
        rawContactId: Long,
        contact: ProjectedContact,
    ): List<ContentProviderOperation> {
        val dataUri = SyncAdapterUri.decorate(Data.CONTENT_URI, account.name, account.type)
        val ops = ArrayList<ContentProviderOperation>()
        // 1) Wipe existing Data rows for this RawContact…
        ops += ContentProviderOperation.newDelete(dataUri)
            .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
            .build()
        // 2) …re-insert against the known stable _ID — no back-references.
        appendChildOps(ops, dataUri, ParentRef.Existing(rawContactId), contact)
        return ops
    }

    private fun appendChildOps(
        ops: MutableList<ContentProviderOperation>,
        dataUri: android.net.Uri,
        parent: ParentRef,
        contact: ProjectedContact,
    ) {
        if (ContactDataOps.hasNameContent(contact)) {
            ContactDataOps.addStructuredName(ops, dataUri, parent, contact)
        }
        contact.emails.forEachIndexed { idx, email ->
            ContactDataOps.addEmail(ops, dataUri, parent, email, primary = idx == 0)
        }
        val phonePrimary = primaryIndex(contact.phones.map { it.isPrimary })
        contact.phones.forEachIndexed { idx, phone ->
            ContactDataOps.addPhone(ops, dataUri, parent, phone, primary = idx == phonePrimary)
        }
        val addressPrimary = primaryIndex(contact.addresses.map { it.isPrimary })
        contact.addresses.forEachIndexed { idx, address ->
            ContactDataOps.addPostal(ops, dataUri, parent, address, primary = idx == addressPrimary)
        }
        contact.organization?.let { ContactDataOps.addOrganization(ops, dataUri, parent, it) }
        contact.notes.forEach { ContactDataOps.addNote(ops, dataUri, parent, it) }
        contact.imAccounts.forEach { ContactDataOps.addIm(ops, dataUri, parent, it) }
        contact.photo?.let { ContactDataOps.addPhoto(ops, dataUri, parent, it.data) }
        contact.urls.forEach { ContactDataOps.addWebsite(ops, dataUri, parent, it) }
    }

    /** Exactly one primary row per kind: the explicit flag if any, else position 0. */
    private fun primaryIndex(flags: List<Boolean>): Int {
        val explicit = flags.indexOfFirst { it }
        return if (explicit >= 0) explicit else 0
    }

    private fun deleteContactOp(account: Account, sourceId: String): ContentProviderOperation =
        ContentProviderOperation.newDelete(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type),
        )
            .withSelection(
                "${RawContacts.SOURCE_ID} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?" +
                    " AND ${RawContacts.ACCOUNT_NAME} = ?",
                arrayOf(sourceId, account.type, account.name),
            )
            .build()
}
