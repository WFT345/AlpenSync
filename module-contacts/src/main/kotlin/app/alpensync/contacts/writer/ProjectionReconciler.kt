// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.writer

import android.provider.ContactsContract.CommonDataKinds.Im
import app.alpensync.contacts.vcard.ProjectedAddress
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedIm
import app.alpensync.contacts.vcard.ProjectedName
import app.alpensync.contacts.vcard.ProjectedPhone
import app.alpensync.contacts.vcard.ProjectedPhoto

/**
 * Reconciles a provider read-back ([RawContactDataReader]) against the stored
 * baseline projection so the dirty-detection hash and the write payload both
 * see full-fidelity data (ADR 0007 Section 2's "hash of the current local
 * vCard vs the stored hash" — the comparison is only meaningful when both
 * sides went through the same fidelity domain).
 *
 * The provider round-trip is lossy in exactly four spots (ContactDataOps):
 * list-valued name pieces collapse to the first, type-token lists collapse to
 * one Android type int, primary flags follow the writer's fixed echo
 * (first row for emails; the flagged/first row for phones+addresses), and
 * photos keep only the downscaled bytes. For every entry the user did NOT
 * touch, the read-back equals the baseline's provider-echo — so the original
 * baseline entry (full tokens, original photo bytes) is reinstated and the
 * unchanged contact reconciles to the baseline EXACTLY. An entry whose
 * read-back differs from the echo is a genuine local edit and keeps the
 * read-back values (a type flip in the Contacts app is detected this way).
 *
 * Fields the provider round-trips verbatim (displayName, organization,
 * notes, urls) pass straight through; fields the writer never emits
 * (birthday, anniversary) are carried from the baseline.
 */
object ProjectionReconciler {

    fun reconcile(
        read: ProjectedContact,
        baseline: ProjectedContact,
        downscale: (ByteArray) -> ByteArray? = PhotoDownscaler::downscale,
    ): ProjectedContact = ProjectedContact(
        protonContactId = read.protonContactId,
        protonUid = baseline.protonUid,
        displayName = read.displayName,
        structuredName = reconcileName(read.structuredName, baseline.structuredName),
        emails = reconcileKeyed(
            read.emails,
            baseline.emails,
            baseline.emails.mapIndexed { i, e -> e.copy(types = emptyList(), isPrimary = i == 0) },
        ) { it.address },
        phones = reconcileKeyed(read.phones, baseline.phones, echoPhones(baseline.phones)) { it.number },
        addresses = reconcileKeyed(read.addresses, baseline.addresses, echoAddresses(baseline.addresses), ::addressKey),
        organization = read.organization,
        notes = reconcileKeyed(read.notes, baseline.notes, baseline.notes) { it },
        imAccounts = reconcileKeyed(
            read.imAccounts,
            baseline.imAccounts,
            baseline.imAccounts.map(::echoIm),
        ) { it.handle + UNIT_SEPARATOR + it.protocol.orEmpty() },
        photo = reconcilePhoto(read.photo, baseline.photo, downscale),
        urls = reconcileKeyed(read.urls, baseline.urls, baseline.urls) { it },
        birthday = baseline.birthday,
        anniversary = baseline.anniversary,
    )

    /**
     * Baseline-order reconciliation: matched entries keep the baseline object
     * when the read-back equals the provider-echo, else the read-back;
     * baseline entries with no read-back match were deleted locally;
     * unmatched read-back entries (new) append in provider order.
     */
    private fun <T : Any> reconcileKeyed(
        read: List<T>,
        baseline: List<T>,
        echoBaseline: List<T>,
        key: (T) -> String,
    ): List<T> {
        val readByKey = LinkedHashMap<String, MutableList<T>>()
        read.forEach { entry -> readByKey.getOrPut(key(entry)) { mutableListOf() } += entry }
        val result = mutableListOf<T>()
        baseline.forEachIndexed { index, baseEntry ->
            val candidates = readByKey[key(baseEntry)] ?: return@forEachIndexed
            val readEntry = candidates.removeFirstOrNull() ?: return@forEachIndexed
            result += if (readEntry == echoBaseline[index]) baseEntry else readEntry
        }
        readByKey.values.forEach { remaining -> result += remaining }
        return result
    }

    /** What the writer + provider do to a baseline structured name: first non-blank per list. */
    private fun reconcileName(read: ProjectedName?, baseline: ProjectedName?): ProjectedName? {
        baseline ?: return read
        val echo = ProjectedName(
            given = baseline.given,
            family = baseline.family,
            additionalNames = baseline.additionalNames.firstOrNull()?.let(::listOf) ?: emptyList(),
            prefixes = baseline.prefixes.firstOrNull()?.let(::listOf) ?: emptyList(),
            suffixes = baseline.suffixes.firstOrNull()?.let(::listOf) ?: emptyList(),
        )
        return if (read == echo) baseline else read
    }

    /** Phone echo: tokens through the Android-type round trip; primary per the writer's rule. */
    private fun echoPhones(baseline: List<ProjectedPhone>): List<ProjectedPhone> {
        val primaryIndex = baseline.indexOfFirst { it.isPrimary }.let { if (it < 0) 0 else it }
        return baseline.mapIndexed { index, phone ->
            phone.copy(
                types = PhoneTypeMapper.fromAndroidType(PhoneTypeMapper.toAndroidType(phone.types)),
                isPrimary = index == primaryIndex,
            )
        }
    }

    /** Address echo: same rule as phones. */
    private fun echoAddresses(baseline: List<ProjectedAddress>): List<ProjectedAddress> {
        val primaryIndex = baseline.indexOfFirst { it.isPrimary }.let { if (it < 0) 0 else it }
        return baseline.mapIndexed { index, address ->
            address.copy(
                types = PostalAddressTypeMapper.fromAndroidType(PostalAddressTypeMapper.toAndroidType(address.types)),
                isPrimary = index == primaryIndex,
            )
        }
    }

    /** IM echo: the scheme round-trips through the protocol int (custom schemes ride CUSTOM_PROTOCOL). */
    private fun echoIm(im: ProjectedIm): ProjectedIm {
        val androidProtocol = ImProtocolMapper.protocolToAndroid(im.protocol)
        val echoProtocol = if (androidProtocol == Im.PROTOCOL_CUSTOM) {
            im.protocol
        } else {
            ImProtocolMapper.protocolFromAndroid(androidProtocol, null)
        }
        return im.copy(
            protocol = echoProtocol,
            types = ImProtocolMapper.typeFromAndroid(ImProtocolMapper.typeToAndroid(im.types)),
        )
    }

    /** Photo echo: only the downscaled bytes survive the provider; the mime type is dropped. */
    private fun reconcilePhoto(
        read: ProjectedPhoto?,
        baseline: ProjectedPhoto?,
        downscale: (ByteArray) -> ByteArray?,
    ): ProjectedPhoto? {
        baseline ?: return read
        val echo = downscale(baseline.data)?.let { ProjectedPhoto(it, mimeType = null) }
        return if (read == echo) baseline else read
    }

    private fun addressKey(address: ProjectedAddress): String = listOf(
        address.poBox, address.extendedAddress, address.street, address.locality,
        address.region, address.postalCode, address.country,
    ).joinToString(UNIT_SEPARATOR) { it.orEmpty() }

    private const val UNIT_SEPARATOR = "\u001F"
}
