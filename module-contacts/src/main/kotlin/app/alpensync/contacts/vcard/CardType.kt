// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-contacts/src/main/kotlin/io/pcontacts/core/protoncontacts/CardType.kt

package app.alpensync.contacts.vcard

/**
 * Mirror of WebClients `packages/shared/lib/contacts/constants.ts`
 * CONTACT_CARD_TYPE (live-verified by pcontacts; research notes Section 1.3).
 * Wire values are integers; never reorder.
 *
 * Per-card crypto semantics:
 *   - [CLEAR_TEXT] — Data is a plaintext vCard fragment; Signature null.
 *   - [ENCRYPTED] — Data is an ASCII-armored OpenPGP message; Signature null.
 *   - [SIGNED] — Data is plaintext; Signature is a detached OpenPGP
 *     signature over Data.
 *   - [ENCRYPTED_AND_SIGNED] — Data is an armored OpenPGP message; Signature
 *     is a detached signature over the DECRYPTED plaintext.
 *
 * The original wire type rides along with the decrypted card because the
 * merge rule "accept UID only from SIGNED cards" needs it
 * (anti-identity-rebinding; research notes Section 1.7).
 */
enum class CardType(val wireValue: Int) {
    CLEAR_TEXT(0),
    ENCRYPTED(1),
    SIGNED(2),
    ENCRYPTED_AND_SIGNED(3),
    ;

    companion object {
        fun fromWire(value: Int): CardType? = entries.firstOrNull { it.wireValue == value }
    }
}
