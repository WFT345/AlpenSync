// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-contacts/.../protoncontacts/ContactSerializer.kt.
// Deviations (the load-bearing one): the input is the full CANONICAL merged
// vCard — not pcontacts' reduced DecryptedContact model — so every unmapped
// property (X-*, BDAY, URL, NICKNAME, CATEGORIES, …) is re-serialized and
// survives a phone-side edit (ADR 0005 Section 7, ADR 0007 Section 3:
// losslessness is mandatory because update is whole-Cards[]-replacement).

package app.alpensync.contacts.vcard

import app.alpensync.core.api.dto.ContactCardDto
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Uid

/**
 * Inverse of [ContactDecrypter] + [VCardMerger]: the canonical vCard → the
 * `Cards[]` the Proton write API expects.
 *
 * Card topology — pcontacts' 2B (ADR 0007 Section 3):
 *   - SIGNED card (type 2): `FN`, `UID` (+ `EMAIL` iff [EMAIL_IN_SIGNED_CARD]).
 *   - ENCRYPTED_AND_SIGNED card (type 3): everything else.
 *
 * The split is a PARTITION: every canonical property lands in exactly one
 * card, so merge(serialize(x)) round-trips losslessly (the M2 merger accepts
 * UID only from SIGNED cards, and ours puts it there).
 */
class ContactSerializer(
    private val encryptOp: CardEncryptOp,
    private val emailInSignedCard: Boolean = EMAIL_IN_SIGNED_CARD,
) {

    fun serialize(canonical: VCard): List<ContactCardDto> {
        val signed = VCard()
        val encrypted = VCard()
        routeProperties(canonical, signed, encrypted)

        val signedText = writeVCard(signed)
        val encryptedText = writeVCard(encrypted)

        val signedOutcome = encryptOp(CardEncryptRequest.SignOnly(signedText))
        val encryptedOutcome = encryptOp(CardEncryptRequest.EncryptAndSign(encryptedText))

        return listOf(
            ContactCardDto(
                type = CardType.SIGNED.wireValue,
                data = signedOutcome.data,
                signature = signedOutcome.signature,
            ),
            ContactCardDto(
                type = CardType.ENCRYPTED_AND_SIGNED.wireValue,
                data = encryptedOutcome.data,
                signature = encryptedOutcome.signature,
            ),
        )
    }

    /**
     * FN and UID are placed explicitly (with the FN fallback chain); every
     * other property routes by class — EMAIL by the seam, the rest to the
     * encrypted card. Property objects are shared, never mutated.
     */
    private fun routeProperties(canonical: VCard, signed: VCard, encrypted: VCard) {
        signed.formattedName = FormattedName(resolveFn(canonical))
        canonical.uid?.value?.takeIf { it.isNotBlank() }?.let { signed.uid = Uid(it) }
        for (property in canonical.properties) {
            when {
                property is FormattedName || property is Uid -> Unit // the identity pair, placed above
                property is Email && emailInSignedCard -> signed.addProperty(property)
                else -> encrypted.addProperty(property)
            }
        }
    }

    /**
     * FN is mandatory in the SIGNED card (vCard 4.0 requires it and Proton
     * web displays it): canonical FN, else first email address, else
     * "Unknown" — pcontacts ContactSerializer.kt:72-84's exact chain.
     */
    private fun resolveFn(canonical: VCard): String =
        canonical.formattedName?.value?.takeIf { it.isNotBlank() }
            ?: canonical.emails.orEmpty().firstNotNullOfOrNull { it.value?.takeIf(String::isNotBlank) }
            ?: UNKNOWN_NAME

    private fun writeVCard(vcard: VCard): String =
        Ezvcard.write(vcard).version(VCardVersion.V4_0).prodId(false).go().trimEnd()

    companion object {
        /**
         * **Topology seam (ADR 0007 Section 3, [SEAM: M3 live probe 1]).**
         * Default false = pcontacts' 2B: EMAIL sits in the ENCRYPTED_AND_SIGNED
         * card. The open question the M3c live probe settles (research notes
         * Section 8.1): whether the server builds its contact-email index only
         * from server-readable (SIGNED/CLEAR_TEXT) cards — if yes, 2B-written
         * contacts vanish from Proton web's composer search. Flipping this one
         * constant routes EMAIL into the SIGNED card (the web/hydroxide
         * topology) for creates and updates alike; nothing else changes.
         */
        const val EMAIL_IN_SIGNED_CARD = false

        private const val UNKNOWN_NAME = "Unknown"
    }
}
