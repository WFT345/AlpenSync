// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Rebuild policy adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/proton-contacts/.../protoncontacts/ContactSerializer.kt
// (buildEncryptedCard half) — applied onto the canonical vCard instead of a
// fresh model, so properties the Android projection does not know survive.

package app.alpensync.contacts.vcard

import app.alpensync.core.api.log.SafeLog
import ezvcard.VCard
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Anniversary
import ezvcard.property.Birthday
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Impp
import ezvcard.property.Note
import ezvcard.property.Organization
import ezvcard.property.Photo
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.Url
import java.net.URI
import java.net.URISyntaxException

/**
 * Applies a phone-side edit ([ProjectedContact], the provider's view) onto
 * the canonical merged vCard, producing the vCard the write path
 * re-serializes. Losslessness is mandatory (ADR 0007 Section 3): update is
 * whole-Cards[]-replacement server-side, so anything dropped here is deleted
 * from Proton.
 *
 * Two rules make it lossless:
 *
 * 1. **Unmapped properties are never touched.** The editor only replaces the
 *    property families the projection owns (FN, N, EMAIL, TEL, ADR, ORG,
 *    TITLE, NOTE, IMPP, URL, BDAY, ANNIVERSARY, PHOTO). X-*, NICKNAME,
 *    CATEGORIES and any unknown property pass through verbatim.
 * 2. **Unchanged families keep their original property objects.** A family
 *    is rebuilt from the projection only when the projected values actually
 *    differ from the canonical baseline's projection. An untouched EMAIL
 *    therefore keeps exotic parameters (group refs, custom types) that a
 *    rebuild could not represent; a rebuilt family carries pcontacts-level
 *    fidelity (types + PREF). Baseline and edit pass through the same
 *    projection code, so comparison is deterministic.
 *
 * **Photo rule (research notes Section 1.4 — the shipped pcontacts bug we
 * must not copy):** the provider's photo is a downscaled copy; pushing it
 * back over Proton's original is silent quality loss. The caller decides via
 * [PhotoUpdate] — the M3b engine derives it from `contact_map.photo_hash`
 * (KEEP when the provider photo hash still matches, REPLACE on a real local
 * photo change). The editor never decides on its own.
 *
 * The returned VCard is a new container; unchanged property OBJECTS are
 * shared with the input (they are never mutated here), so the caller's
 * canonical stays intact.
 */
object CanonicalVCardEditor {

    /** What happens to the PHOTO family; see the class KDoc for the rule. */
    enum class PhotoUpdate {
        /** Canonical Photo properties stay verbatim (Proton's original bytes). */
        KEEP_SERVER_BYTES,

        /** Canonical photos replaced by the projection's (a real local photo change). */
        REPLACE_FROM_PROJECTION,

        /** All photos removed (local photo deleted). */
        REMOVE,
    }

    fun applyEdits(canonical: VCard, edited: ProjectedContact, photoUpdate: PhotoUpdate): VCard {
        val baseline = ContactProjection.project(
            CanonicalContact(
                protonContactId = edited.protonContactId,
                vcard = canonical,
                protonUid = edited.protonUid,
                verified = true,
                cardCount = 0,
                unverifiedCardCount = 0,
                malformedFragmentCount = 0,
            ),
        )
        val updated = VCard()
        canonical.properties.forEach { updated.addProperty(it) }

        applyNames(updated, baseline, edited)
        applyEmails(updated, baseline, edited)
        applyPhones(updated, baseline, edited)
        applyAddresses(updated, baseline, edited)
        applyOrganization(updated, baseline, edited)
        applyNotes(updated, baseline, edited)
        applyImpps(updated, baseline, edited)
        applyUrls(updated, baseline, edited)
        applyDateProperty(updated, Birthday::class.java, baseline.birthday, edited.birthday, ::Birthday)
        applyDateProperty(updated, Anniversary::class.java, baseline.anniversary, edited.anniversary, ::Anniversary)
        applyPhoto(updated, edited, photoUpdate)
        return updated
    }

    private fun applyNames(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.displayName == edited.displayName && baseline.structuredName == edited.structuredName) return
        updated.removeProperties(FormattedName::class.java)
        updated.removeProperties(StructuredName::class.java)
        edited.displayName?.takeIf { it.isNotBlank() }?.let { updated.addProperty(FormattedName(it)) }
        buildStructuredName(edited.structuredName)?.let { updated.addProperty(it) }
    }

    private fun applyEmails(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.emails == edited.emails) return
        updated.removeProperties(Email::class.java)
        edited.emails.forEach { email -> updated.addProperty(buildEmail(email)) }
    }

    private fun applyPhones(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.phones == edited.phones) return
        updated.removeProperties(Telephone::class.java)
        edited.phones.forEach { phone -> updated.addProperty(buildPhone(phone)) }
    }

    private fun applyAddresses(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.addresses == edited.addresses) return
        updated.removeProperties(Address::class.java)
        edited.addresses.forEach { address -> updated.addProperty(buildAddress(address)) }
    }

    private fun applyOrganization(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.organization == edited.organization) return
        updated.removeProperties(Organization::class.java)
        updated.removeProperties(Title::class.java)
        val org = edited.organization ?: return
        buildOrganization(org)?.let { updated.addProperty(it) }
        org.title?.let { updated.addProperty(Title(it)) }
    }

    private fun applyNotes(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.notes == edited.notes) return
        updated.removeProperties(Note::class.java)
        edited.notes.forEach { updated.addProperty(Note(it)) }
    }

    private fun applyImpps(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.imAccounts == edited.imAccounts) return
        updated.removeProperties(Impp::class.java)
        edited.imAccounts.forEach { im -> buildImpp(im)?.let { updated.addProperty(it) } }
    }

    private fun applyUrls(updated: VCard, baseline: ProjectedContact, edited: ProjectedContact) {
        if (baseline.urls == edited.urls) return
        updated.removeProperties(Url::class.java)
        edited.urls.forEach { updated.addProperty(Url(it)) }
    }

    /**
     * BDAY/ANNIVERSARY ride as their vCard value strings in the projection.
     * Unchanged → the original property object survives verbatim (no date
     * format round-trip at all). Changed → the string becomes the new value;
     * Android supplies ISO-ish date text and the Proton side treats it as
     * the partial-date/text form (the projection reads it back identically).
     */
    private fun <T : ezvcard.property.DateOrTimeProperty> applyDateProperty(
        updated: VCard,
        family: Class<T>,
        baselineValue: String?,
        editedValue: String?,
        construct: (String) -> T,
    ) {
        if (baselineValue == editedValue) return
        updated.removeProperties(family)
        editedValue?.takeIf { it.isNotBlank() }?.let { updated.addProperty(construct(it)) }
    }

    private fun applyPhoto(updated: VCard, edited: ProjectedContact, photoUpdate: PhotoUpdate) {
        when (photoUpdate) {
            PhotoUpdate.KEEP_SERVER_BYTES -> Unit
            PhotoUpdate.REMOVE -> updated.removeProperties(Photo::class.java)
            PhotoUpdate.REPLACE_FROM_PROJECTION -> {
                updated.removeProperties(Photo::class.java)
                val photo = edited.photo
                if (photo != null && photo.data.isNotEmpty()) {
                    updated.addProperty(Photo(photo.data, null))
                }
            }
        }
    }

    // --- rebuild helpers (pcontacts ContactSerializer.kt:102-177, verbatim policy) ---

    private fun buildStructuredName(name: ProjectedName?): StructuredName? {
        name ?: return null
        return StructuredName().apply {
            given = name.given
            family = name.family
            name.additionalNames.forEach { additionalNames.add(it) }
            name.prefixes.forEach { prefixes.add(it) }
            name.suffixes.forEach { suffixes.add(it) }
        }
    }

    private fun buildEmail(email: ProjectedEmail): Email {
        val built = Email(email.address)
        email.types.forEach { token -> EmailType.find(token)?.let { built.types.add(it) } }
        if (email.isPrimary) built.pref = 1
        return built
    }

    private fun buildPhone(phone: ProjectedPhone): Telephone {
        val built = Telephone(phone.number)
        phone.types.forEach { token -> TelephoneType.find(token)?.let { built.types.add(it) } }
        if (phone.isPrimary) built.pref = 1
        return built
    }

    private fun buildAddress(address: ProjectedAddress): Address {
        val built = Address()
        built.poBox = address.poBox
        built.extendedAddress = address.extendedAddress
        built.streetAddress = address.street
        built.locality = address.locality
        built.region = address.region
        built.postalCode = address.postalCode
        built.country = address.country
        address.types.forEach { token -> AddressType.find(token)?.let { built.types.add(it) } }
        if (address.isPrimary) built.pref = 1
        return built
    }

    private fun buildOrganization(org: ProjectedOrganization): Organization? {
        if (org.company == null && org.department == null) return null
        val built = Organization()
        org.company?.let { built.values.add(it) }
        org.department?.let {
            if (built.values.isEmpty()) built.values.add("")
            built.values.add(it)
        }
        return built
    }

    /** No scheme → not serializable as a URI (pcontacts' policy); skipped, logged, never fatal. */
    private fun buildImpp(im: ProjectedIm): Impp? {
        val scheme = im.protocol?.takeIf { it.isNotBlank() } ?: return null
        val handle = im.handle.takeIf { it.isNotBlank() } ?: return null
        return try {
            Impp(URI("$scheme:$handle"))
        } catch (ignored: URISyntaxException) {
            SafeLog.log(SafeLog.Event.CONTACT_WRITE_IMPP_SKIPPED)
            null
        }
    }
}
