// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-contacts/.../protoncontacts/VCardMerger.kt (projection half).
// Deviations: operates on the canonical merged VCard instead of being fused
// into the merge; adds URL + BDAY/ANNIVERSARY projection per the M2b scope;
// emails/phones are ordered primary-first (vCard PREF=1 = primary).

package app.alpensync.contacts.vcard

import ezvcard.VCard
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.ImppType
import ezvcard.parameter.TelephoneType
import ezvcard.property.DateOrTimeProperty

/**
 * Pure projection: canonical merged vCard → the field set M2 writes to
 * ContactsContract (the actual provider ops are M2d). No Android imports, no
 * IO — every function here is fuzz-exercised by the unit tests.
 */
object ContactProjection {

    fun project(canonical: CanonicalContact): ProjectedContact {
        val merged = canonical.vcard
        return ProjectedContact(
            protonContactId = canonical.protonContactId,
            protonUid = canonical.protonUid,
            displayName = displayName(merged),
            structuredName = structuredName(merged),
            emails = emails(merged),
            phones = phones(merged),
            addresses = addresses(merged),
            organization = organization(merged),
            notes = merged.notes.orEmpty().mapNotNull { it.value?.takeIf(String::isNotBlank) },
            imAccounts = impps(merged),
            photo = photo(merged),
            urls = merged.urls.orEmpty().mapNotNull { it.value?.takeIf(String::isNotBlank) },
            birthday = merged.birthday?.let(::dateOrText),
            anniversary = merged.anniversary?.let(::dateOrText),
        )
    }

    /** FN wins; absent FN is synthesized from N pieces; both absent → null. */
    private fun displayName(merged: VCard): String? =
        merged.formattedName?.value?.takeIf { it.isNotBlank() } ?: deriveFnFromN(merged)

    private fun structuredName(merged: VCard): ProjectedName? {
        val n = merged.structuredName ?: return null
        val given = n.given?.takeIf { it.isNotBlank() }
        val family = n.family?.takeIf { it.isNotBlank() }
        val additional = n.additionalNames.orEmpty().filter { it.isNotBlank() }
        val prefixes = n.prefixes.orEmpty().filter { it.isNotBlank() }
        val suffixes = n.suffixes.orEmpty().filter { it.isNotBlank() }
        if (given == null && family == null && additional.isEmpty() && prefixes.isEmpty() && suffixes.isEmpty()) {
            return null
        }
        return ProjectedName(given, family, additional, prefixes, suffixes)
    }

    private fun deriveFnFromN(merged: VCard): String? {
        val n = merged.structuredName ?: return null
        val parts = listOfNotNull(
            n.prefixes?.firstOrNull()?.takeIf { it.isNotBlank() },
            n.given?.takeIf { it.isNotBlank() },
            n.additionalNames?.firstOrNull()?.takeIf { it.isNotBlank() },
            n.family?.takeIf { it.isNotBlank() },
            n.suffixes?.firstOrNull()?.takeIf { it.isNotBlank() },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun emails(merged: VCard): List<ProjectedEmail> = merged.emails.orEmpty()
        .mapNotNull { e ->
            e.value?.takeIf { it.isNotBlank() }?.let { address ->
                ProjectedEmail(address, e.types.orEmpty().map(EmailType::getValue), isPrimary(e.pref))
            }
        }
        .sortedByDescending { it.isPrimary }

    private fun phones(merged: VCard): List<ProjectedPhone> = merged.telephoneNumbers.orEmpty()
        .mapNotNull { t ->
            // ez-vcard: text is the standard string form (RFC 6350 §6.4.1);
            // uri covers the tel:-style form. The number is kept verbatim.
            val number = t.text?.takeIf { it.isNotBlank() } ?: t.uri?.toString()
            number?.takeIf { it.isNotBlank() }?.let {
                ProjectedPhone(it, t.types.orEmpty().map(TelephoneType::getValue), isPrimary(t.pref))
            }
        }
        .sortedByDescending { it.isPrimary }

    private fun addresses(merged: VCard): List<ProjectedAddress> = merged.addresses.orEmpty()
        .map { a ->
            ProjectedAddress(
                poBox = a.poBox?.takeIf { it.isNotBlank() },
                extendedAddress = a.extendedAddress?.takeIf { it.isNotBlank() },
                street = a.streetAddress?.takeIf { it.isNotBlank() },
                locality = a.locality?.takeIf { it.isNotBlank() },
                region = a.region?.takeIf { it.isNotBlank() },
                postalCode = a.postalCode?.takeIf { it.isNotBlank() },
                country = a.country?.takeIf { it.isNotBlank() },
                types = a.types.orEmpty().map(AddressType::getValue),
                isPrimary = isPrimary(a.pref),
            )
        }
        .filter {
            // Blank-only addresses carry no information; drop them.
            listOfNotNull(
                it.poBox, it.extendedAddress, it.street, it.locality,
                it.region, it.postalCode, it.country,
            ).isNotEmpty()
        }

    private fun organization(merged: VCard): ProjectedOrganization? {
        val values = merged.organization?.values.orEmpty()
        val company = values.getOrNull(0)?.takeIf { it.isNotBlank() }
        val department = values.getOrNull(1)?.takeIf { it.isNotBlank() }
        val title = merged.titles.orEmpty().firstOrNull()?.value?.takeIf { it.isNotBlank() }
        if (company == null && department == null && title == null) return null
        return ProjectedOrganization(company, department, title)
    }

    private fun impps(merged: VCard): List<ProjectedIm> = merged.impps.orEmpty().mapNotNull { impp ->
        val uri = impp.uri ?: return@mapNotNull null
        val handle = uri.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ProjectedIm(handle, uri.scheme?.takeIf { it.isNotBlank() }, impp.types.orEmpty().map(ImppType::getValue))
    }

    /** First inline PHOTO only (one photo slot per RawContact); URL-reference photos skipped. */
    private fun photo(merged: VCard): ProjectedPhoto? {
        val first = merged.photos.orEmpty().firstOrNull { it.data?.isNotEmpty() == true } ?: return null
        val data = first.data ?: return null
        return ProjectedPhoto(data, first.contentType?.mediaType?.takeIf { it.isNotBlank() })
    }
}

/**
 * BDAY/ANNIVERSARY as their vCard value string: free text when present,
 * else partial date (e.g. "--04-21"), else the ISO form of the parsed
 * date. Keeping the string form avoids inventing timezone semantics the
 * source never had.
 */
private fun dateOrText(property: DateOrTimeProperty): String? =
    property.text?.takeIf { it.isNotBlank() }
        ?: property.partialDate?.toISO8601(false)
        ?: property.date?.toString()

/** vCard PREF: lowest numeric wins; PREF=1 marks the primary. */
private fun isPrimary(pref: Int?): Boolean = (pref ?: Int.MAX_VALUE) == 1
