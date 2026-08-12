// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-contacts/.../protoncontacts/VCardMerger.kt (merge half only).
// Deviation (the load-bearing one, ADR 0005 Section 7): the merged ez-vcard
// VCard IS the retained canonical form — pcontacts projected into a fixed
// Kotlin model here and dropped every unmapped property (BDAY, URL, NICKNAME,
// CATEGORIES, X-*, …), a lossy-parse bug M3 write-back would turn into
// server-side data loss (research notes Section 3.5). Their projection half
// lives separately in ContactProjection.kt as a pure view.

package app.alpensync.contacts.vcard

import app.alpensync.core.api.log.SafeLog
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.property.Uid

/**
 * Merges all decrypted vCard fragments of one contact into the canonical
 * [CanonicalContact].
 *
 * Rules (research notes Sections 1.7, 3.1):
 *   - Parse each fragment with ez-vcard (tolerates vCard 2.1/3.0 imports;
 *     Proton emits 4.0).
 *   - Discard `UID` properties from non-SIGNED cards — a tampered ENCRYPTED
 *     card could otherwise rebind the contact's identity.
 *   - Every other property accumulates, including X-* and unknown ones.
 *   - Malformed fragments are counted and skipped, never fatal.
 */
object VCardMerger {

    fun merge(protonContactId: String, decrypted: List<DecryptedCard>): CanonicalContact {
        val merged = VCard()
        var malformed = 0
        for (card in decrypted) {
            if (!mergeFragment(merged, card)) malformed += 1
        }

        if (malformed > 0) {
            SafeLog.log(SafeLog.Event.CONTACT_VCARD_FRAGMENT_MALFORMED, malformed)
        }

        val unverified = decrypted.count { it.signatureBearingAndFailed() }
        if (unverified > 0) {
            SafeLog.log(SafeLog.Event.CONTACT_CARDS_UNVERIFIED, unverified)
        }

        return CanonicalContact(
            protonContactId = protonContactId,
            vcard = merged,
            protonUid = merged.uid?.value?.takeIf { it.isNotBlank() },
            verified = unverified == 0,
            cardCount = decrypted.size,
            unverifiedCardCount = unverified,
            malformedFragmentCount = malformed,
        )
    }

    /**
     * Parses one fragment and accumulates its properties into [merged].
     * Returns false when the fragment is malformed (caller counts + skips).
     * `UID` properties from non-SIGNED cards are discarded — a tampered
     * ENCRYPTED card could otherwise rebind the contact's identity.
     */
    private fun mergeFragment(merged: VCard, card: DecryptedCard): Boolean {
        val fragment = parseFragment(card.plaintext) ?: return false
        for (property in fragment.properties) {
            if (property is Uid && card.originalType != CardType.SIGNED) continue
            merged.addProperty(property)
        }
        return true
    }

    private fun DecryptedCard.signatureBearingAndFailed(): Boolean =
        !verified && (originalType == CardType.SIGNED || originalType == CardType.ENCRYPTED_AND_SIGNED)

    /**
     * ez-vcard is deliberately lenient (parse warnings, not exceptions), but
     * hostile or truncated input can still make it throw arbitrary unchecked
     * exceptions. Plan Rule 5 demands fail-closed behavior for ANY parser
     * blowup, hence the broad catch: a fragment that fails to parse is
     * counted as malformed and skipped, never propagated.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun parseFragment(plaintext: String): VCard? = try {
        Ezvcard.parse(plaintext).first()
    } catch (ignored: Exception) {
        null
    }
}
