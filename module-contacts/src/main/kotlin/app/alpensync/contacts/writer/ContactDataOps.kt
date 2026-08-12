// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../ContactsContractOps.kt (the per-kind Data-row
// builders). Deviation: ONE builder set instead of their duplicated
// withBackRef/forExisting pairs — the parent RawContact is attached through
// the [ParentRef] seam, so the row content is built exactly once.

package app.alpensync.contacts.writer

import android.content.ContentProviderOperation
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import app.alpensync.contacts.vcard.ProjectedAddress
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedIm
import app.alpensync.contacts.vcard.ProjectedName
import app.alpensync.contacts.vcard.ProjectedOrganization
import app.alpensync.contacts.vcard.ProjectedPhone
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName

/**
 * How a child Data row learns its parent RawContact: a back-reference into
 * the batch (Create — the RawContacts._ID isn't known yet) or the stable
 * literal _ID (Update — ADR 0005 Section 6's delete-and-reinsert).
 */
internal sealed interface ParentRef {
    @JvmInline
    value class BackReference(val batchIndex: Int) : ParentRef

    @JvmInline
    value class Existing(val rawContactId: Long) : ParentRef
}

private fun ContentProviderOperation.Builder.withParent(parent: ParentRef) = apply {
    when (parent) {
        is ParentRef.BackReference -> withValueBackReference(Data.RAW_CONTACT_ID, parent.batchIndex)
        is ParentRef.Existing -> withValue(Data.RAW_CONTACT_ID, parent.rawContactId)
    }
}

private fun ContentProviderOperation.Builder.withPrimary(
    primaryColumn: String,
    superColumn: String,
    primary: Boolean,
) = apply {
    if (primary) {
        withValue(primaryColumn, 1)
        withValue(superColumn, 1)
    }
}

/** ContactsContract has one column per name piece — multi-values collapse to the first non-blank. */
private fun applyNamePieces(builder: ContentProviderOperation.Builder, name: ProjectedName?) {
    if (name == null) return
    name.given?.takeIf { it.isNotBlank() }?.let { builder.withValue(CCStructuredName.GIVEN_NAME, it) }
    name.family?.takeIf { it.isNotBlank() }?.let { builder.withValue(CCStructuredName.FAMILY_NAME, it) }
    name.additionalNames.firstOrNull { it.isNotBlank() }
        ?.let { builder.withValue(CCStructuredName.MIDDLE_NAME, it) }
    name.prefixes.firstOrNull { it.isNotBlank() }?.let { builder.withValue(CCStructuredName.PREFIX, it) }
    name.suffixes.firstOrNull { it.isNotBlank() }?.let { builder.withValue(CCStructuredName.SUFFIX, it) }
}

private typealias OpSink = MutableList<ContentProviderOperation>

/** The per-kind Data-row builders; each appends exactly one op to the sink. */
internal object ContactDataOps {

    /**
     * Emitted ONLY when Proton supplied a real name — a blank displayName and
     * no N pieces means NO StructuredName row at all, so Android's aggregator
     * can adopt a peer RawContact's name instead of stamping a phone-number
     * string over it (the "Bob vs Bob Smith" case, research notes §3.3).
     */
    fun hasNameContent(contact: ProjectedContact): Boolean {
        if (!contact.displayName.isNullOrBlank()) return true
        val name = contact.structuredName ?: return false
        return !name.given.isNullOrBlank() || !name.family.isNullOrBlank() ||
            name.additionalNames.any { it.isNotBlank() } ||
            name.prefixes.any { it.isNotBlank() } || name.suffixes.any { it.isNotBlank() }
    }

    fun addStructuredName(out: OpSink, uri: Uri, parent: ParentRef, contact: ProjectedContact) {
        val builder = ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, CCStructuredName.CONTENT_ITEM_TYPE)
        contact.displayName?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.DISPLAY_NAME, it) }
        applyNamePieces(builder, contact.structuredName)
        out += builder.build()
    }

    fun addEmail(out: OpSink, uri: Uri, parent: ParentRef, email: ProjectedEmail, primary: Boolean) {
        out += ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, email.address)
            .withValue(Email.TYPE, Email.TYPE_OTHER)
            .withPrimary(Email.IS_PRIMARY, Email.IS_SUPER_PRIMARY, primary)
            .build()
    }

    /** Numbers are written VERBATIM — no normalization (ADR 0005 Section 6). */
    fun addPhone(out: OpSink, uri: Uri, parent: ParentRef, phone: ProjectedPhone, primary: Boolean) {
        out += ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            .withValue(Phone.NUMBER, phone.number)
            .withValue(Phone.TYPE, PhoneTypeMapper.toAndroidType(phone.types))
            .withPrimary(Phone.IS_PRIMARY, Phone.IS_SUPER_PRIMARY, primary)
            .build()
    }

    fun addPostal(out: OpSink, uri: Uri, parent: ParentRef, address: ProjectedAddress, primary: Boolean) {
        val builder = ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
            .withValue(StructuredPostal.TYPE, PostalAddressTypeMapper.toAndroidType(address.types))
        address.street?.takeIf { it.isNotBlank() }?.let { builder.withValue(StructuredPostal.STREET, it) }
        address.poBox?.takeIf { it.isNotBlank() }?.let { builder.withValue(StructuredPostal.POBOX, it) }
        address.extendedAddress?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.NEIGHBORHOOD, it) }
        address.locality?.takeIf { it.isNotBlank() }?.let { builder.withValue(StructuredPostal.CITY, it) }
        address.region?.takeIf { it.isNotBlank() }?.let { builder.withValue(StructuredPostal.REGION, it) }
        address.postalCode?.takeIf { it.isNotBlank() }?.let { builder.withValue(StructuredPostal.POSTCODE, it) }
        address.country?.takeIf { it.isNotBlank() }?.let { builder.withValue(StructuredPostal.COUNTRY, it) }
        builder.withPrimary(StructuredPostal.IS_PRIMARY, StructuredPostal.IS_SUPER_PRIMARY, primary)
        out += builder.build()
    }

    fun addOrganization(out: OpSink, uri: Uri, parent: ParentRef, org: ProjectedOrganization) {
        val builder = ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, CCOrganization.CONTENT_ITEM_TYPE)
        org.company?.takeIf { it.isNotBlank() }?.let { builder.withValue(CCOrganization.COMPANY, it) }
        org.department?.takeIf { it.isNotBlank() }?.let { builder.withValue(CCOrganization.DEPARTMENT, it) }
        org.title?.takeIf { it.isNotBlank() }?.let { builder.withValue(CCOrganization.TITLE, it) }
        out += builder.build()
    }

    fun addNote(out: OpSink, uri: Uri, parent: ParentRef, note: String) {
        out += ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
            .withValue(Note.NOTE, note)
            .build()
    }

    fun addIm(out: OpSink, uri: Uri, parent: ParentRef, im: ProjectedIm) {
        val protocol = ImProtocolMapper.protocolToAndroid(im.protocol)
        val builder = ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
            .withValue(Im.DATA, im.handle)
            .withValue(Im.TYPE, ImProtocolMapper.typeToAndroid(im.types))
            .withValue(Im.PROTOCOL, protocol)
        if (protocol == Im.PROTOCOL_CUSTOM) {
            // ContactsContract requires a label when PROTOCOL == CUSTOM.
            builder.withValue(Im.CUSTOM_PROTOCOL, im.protocol?.takeIf { it.isNotBlank() } ?: "im")
        }
        out += builder.build()
    }

    /** First inline photo, downscaled to the ~96 KB inline cap; dropped when it can't fit. */
    fun addPhoto(out: OpSink, uri: Uri, parent: ParentRef, data: ByteArray) {
        val fitted = PhotoDownscaler.downscale(data) ?: return
        out += ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            .withValue(Photo.PHOTO, fitted)
            .build()
    }

    fun addWebsite(out: OpSink, uri: Uri, parent: ParentRef, url: String) {
        out += ContentProviderOperation.newInsert(uri)
            .withParent(parent)
            .withValue(Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
            .withValue(Website.URL, url)
            .withValue(Website.TYPE, Website.TYPE_OTHER)
            .build()
    }
}
