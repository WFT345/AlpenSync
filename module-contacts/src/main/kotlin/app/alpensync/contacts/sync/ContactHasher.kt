// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Hash policy informed by pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts
// @ bf9b0c5, path core/sync/.../EmailSyncHash.kt (SHA-256 over the projected
// shape, photo bytes bit-exact). Deviations: photo hashed into a SEPARATE
// digest so the differ can classify photo-only changes (M2c scope).

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.ProjectedAddress
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedPhone
import java.security.MessageDigest

/**
 * Deterministic change-detection hashes over the PROJECTED contact (the
 * field set M2 writes to ContactsContract). Hashing the projection — not the
 * raw vCard — keeps changes to unmapped properties (X-*, PRODID, …) from
 * causing pointless provider rewrites (research notes Section 3.5).
 *
 * [contentHash] deliberately EXCLUDES the photo; [photoHash] covers the
 * inline photo bytes bit-exactly (plus MIME type). A photo-only change is
 * then distinguishable from a content change and the writer can take the
 * cheap path.
 *
 * No name/phone normalization of any kind happens here: "Bob" vs "Bob
 * Smith" is just a content change, and phone numbers hash verbatim
 * (ADR 0005 Section 6).
 */
object ContactHasher {

    fun contentHash(contact: ProjectedContact): String =
        sha256Hex(canonicalForm(contact).toByteArray(Charsets.UTF_8))

    /** Null when the contact carries no inline photo. */
    fun photoHash(contact: ProjectedContact): String? {
        val photo = contact.photo ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((photo.mimeType ?: "").toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(photo.data)
        return digest.digest().toHex()
    }

    private fun canonicalForm(contact: ProjectedContact): String = buildString {
        names(contact)
        emails(contact.emails)
        phones(contact.phones)
        addresses(contact.addresses)
        scalar("org.company", contact.organization?.company)
        scalar("org.dept", contact.organization?.department)
        scalar("org.title", contact.organization?.title)
        list("note", contact.notes)
        contact.imAccounts.forEach { im ->
            scalar("im.handle", im.handle)
            scalar("im.proto", im.protocol)
            list("im.type", im.types.sorted())
        }
        list("url", contact.urls)
        scalar("bday", contact.birthday)
        scalar("anniv", contact.anniversary)
    }

    private fun StringBuilder.names(contact: ProjectedContact) {
        scalar("fn", contact.displayName)
        val name = contact.structuredName
        scalar("n.given", name?.given)
        scalar("n.family", name?.family)
        list("n.additional", name?.additionalNames)
        list("n.prefixes", name?.prefixes)
        list("n.suffixes", name?.suffixes)
    }

    private fun StringBuilder.emails(emails: List<ProjectedEmail>) {
        emails.forEach { email ->
            scalar("email", email.address)
            list("email.type", email.types.sorted())
            scalar("email.primary", email.isPrimary.toString())
        }
    }

    private fun StringBuilder.phones(phones: List<ProjectedPhone>) {
        phones.forEach { phone ->
            scalar("tel", phone.number)
            list("tel.type", phone.types.sorted())
            scalar("tel.primary", phone.isPrimary.toString())
        }
    }

    private fun StringBuilder.addresses(addresses: List<ProjectedAddress>) {
        addresses.forEach { address ->
            scalar("adr.pobox", address.poBox)
            scalar("adr.ext", address.extendedAddress)
            scalar("adr.street", address.street)
            scalar("adr.city", address.locality)
            scalar("adr.region", address.region)
            scalar("adr.code", address.postalCode)
            scalar("adr.country", address.country)
            list("adr.type", address.types.sorted())
            scalar("adr.primary", address.isPrimary.toString())
        }
    }

    /**
     * Length-prefixed, separator-free encoding: `<name>=<len>:<value>|`,
     * null as `<name>=-1:|`. Unambiguous without escaping — "ab"+"c" and
     * "a"+"bc" cannot collide, and null cannot pose as the empty string.
     */
    private fun StringBuilder.scalar(name: String, value: String?) {
        append(name).append('=')
        if (value == null) {
            append("-1:|")
        } else {
            append(value.length).append(':').append(value).append('|')
        }
    }

    private fun StringBuilder.list(name: String, values: List<String>?) {
        append(name).append('[').append(values?.size?.toString() ?: "-1").append(']')
        values?.forEach { append(it.length).append(':').append(it).append('|') }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
