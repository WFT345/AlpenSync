// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.vcard

import ezvcard.VCard

/**
 * The canonical local form of one Proton contact (ADR 0005 Section 7): the
 * MERGED ez-vcard [VCard] — every property from every decrypted card,
 * including X-* properties and fields the Android projection ignores (BDAY,
 * URL, NICKNAME, CATEGORIES, …).
 *
 * This deliberately inverts pcontacts' design, where the parse-time
 * projection into a fixed Kotlin model was the only retained form and
 * unmapped properties were dropped — harmless read-only, a data-loss bug on
 * M3 write-back (research notes Section 3.5). Here the projection
 * ([app.alpensync.contacts.vcard.ProjectedContact]) is a VIEW over this
 * canonical form; M3 re-serializes from [vcard] with local edits applied so
 * unmapped properties survive a phone-side edit.
 *
 * M2 persisted nothing decrypted; M3a added the Keystore-wrapped canonical
 * store (ADR 0007 Section 5(i), THREAT_MODEL.md) so the write path and the
 * ADR 0006 merge have a lossless base — the at-rest form is ciphertext only.
 *
 * [verified] is true iff every signature-bearing card verified; the count
 * surfaces in the sync report (ADR 0005 risk register). [protonUid] is the
 * vCard UID accepted ONLY from SIGNED cards (the merger already discarded
 * non-SIGNED UIDs — research notes Section 1.7).
 */
data class CanonicalContact(
    val protonContactId: String,
    val vcard: VCard,
    val protonUid: String?,
    val verified: Boolean,
    val cardCount: Int,
    val unverifiedCardCount: Int,
    val malformedFragmentCount: Int,
) {
    companion object {
        /**
         * Wraps an already-merged vCard for projection-only use (write path:
         * the stored canonical baseline, an update candidate) — no card-level
         * provenance applies, so the counts are zero and [verified] is true.
         */
        fun ofVCard(protonContactId: String, vcard: VCard): CanonicalContact = CanonicalContact(
            protonContactId = protonContactId,
            vcard = vcard,
            protonUid = vcard.uid?.value?.takeIf { it.isNotBlank() },
            verified = true,
            cardCount = 0,
            unverifiedCardCount = 0,
            malformedFragmentCount = 0,
        )
    }
}
