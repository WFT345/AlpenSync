// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Model shape adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/proton-contacts/.../protoncontacts/DecryptedContact.kt — extended with
// urls/birthday/anniversary per the M2b scope; types stay STRINGS (the
// ContactsContract int mapping is the M2d writer's job, keeping this package
// free of Android imports).

package app.alpensync.contacts.vcard

/** Structured name pieces; null/empty when the vCard has no usable `N`. */
data class ProjectedName(
    val given: String?,
    val family: String?,
    val additionalNames: List<String>,
    val prefixes: List<String>,
    val suffixes: List<String>,
)

data class ProjectedEmail(
    val address: String,
    val types: List<String>,
    val isPrimary: Boolean,
)

/** Phone numbers are carried VERBATIM — no normalization anywhere (ADR 0005 Section 6). */
data class ProjectedPhone(
    val number: String,
    val types: List<String>,
    val isPrimary: Boolean,
)

data class ProjectedAddress(
    val poBox: String?,
    val extendedAddress: String?,
    val street: String?,
    val locality: String?,
    val region: String?,
    val postalCode: String?,
    val country: String?,
    val types: List<String>,
    val isPrimary: Boolean,
)

data class ProjectedOrganization(
    val company: String?,
    val department: String?,
    val title: String?,
)

data class ProjectedIm(
    val handle: String,
    val protocol: String?,
    val types: List<String>,
)

data class ProjectedPhoto(
    val data: ByteArray,
    val mimeType: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is ProjectedPhoto && data.contentEquals(other.data) && mimeType == other.mimeType

    override fun hashCode(): Int = 31 * data.contentHashCode() + (mimeType?.hashCode() ?: 0)
}

/**
 * The Android-bound VIEW of a [CanonicalContact] (ADR 0005 Section 7 — the
 * canonical merged vCard stays the source of truth; this is a projection,
 * never stored). Carries exactly the field set M2 writes to ContactsContract:
 * names, emails, phones, addresses, org/title, notes, IMs, photo, URLs,
 * birthday/anniversary. Pure data, no ContactsContract references.
 *
 * [displayName] is null when the vCard has neither FN nor any N piece — the
 * writer then writes NO StructuredName row at all, so Android's aggregator
 * can adopt a peer raw contact's name (the "Bob vs Bob Smith" case,
 * research notes Section 3.3).
 */
data class ProjectedContact(
    val protonContactId: String,
    val protonUid: String?,
    val displayName: String?,
    val structuredName: ProjectedName?,
    val emails: List<ProjectedEmail>,
    val phones: List<ProjectedPhone>,
    val addresses: List<ProjectedAddress>,
    val organization: ProjectedOrganization?,
    val notes: List<String>,
    val imAccounts: List<ProjectedIm>,
    val photo: ProjectedPhoto?,
    val urls: List<String>,
    val birthday: String?,
    val anniversary: String?,
) {
    /**
     * The accepted name-only-contact gap (ADR 0005 open question 3): a
     * contact with no email, phone, address, or IM is NOT written to the
     * provider at M2 — the Proton copy is untouched, so this is a coverage
     * gap, not data loss. Exposed as a predicate so the M2d writer applies
     * the guard uniformly.
     */
    fun hasSyncableFields(): Boolean =
        emails.isNotEmpty() || phones.isNotEmpty() || addresses.isNotEmpty() || imAccounts.isNotEmpty()
}
