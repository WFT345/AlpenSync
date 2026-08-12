// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-contacts/.../protoncontacts/ContactDecrypter.kt
// Deviations: per-card failures are returned as typed entries (not just
// logged) so the sync engine can count them loudly per plan Rule 5, and wire
// size caps are enforced before any crypto work (fail-closed on hostile
// payloads).

package app.alpensync.contacts.vcard

import app.alpensync.core.api.dto.ContactCardDto
import app.alpensync.core.api.log.SafeLog

/** Why a card was dropped before/during crypto. Never carries content — only structure. */
enum class CardFailureReason {
    /** Wire `Type` is not 0..3. */
    UNKNOWN_TYPE,

    /** `Data` exceeds [ContactDecrypter.MAX_CARD_CHARS]. */
    CARD_TOO_LARGE,

    /** Decrypted plaintext exceeds [ContactDecrypter.MAX_PLAINTEXT_BYTES]. */
    PLAINTEXT_TOO_LARGE,

    /** `Data` is blank — no fragment to work with. */
    EMPTY_CARD,

    /** Cards beyond [ContactDecrypter.MAX_CARDS_PER_CONTACT] were refused. */
    TOO_MANY_CARDS,

    /** OpenPGP layer failed (no matching key, corrupt armor, truncated base64). */
    DECRYPT_FAILED,
}

/** One dropped card. [wireType] is the raw wire value (may be outside 0..3). */
data class CardFailure(
    val cardIndex: Int,
    val wireType: Int,
    val reason: CardFailureReason,
)

/**
 * Outcome of processing one contact's `Cards[]`: the successfully decoded
 * cards plus one typed failure entry per dropped card. Nothing is dropped
 * silently — the sync engine counts [failures] into the sync report.
 */
data class ContactCardsResult(
    val cards: List<DecryptedCard>,
    val failures: List<CardFailure>,
)

/**
 * Routes each card of a contact into the right crypto path (verify-only,
 * decrypt-only, decrypt-and-verify) via the injected [cryptoOp].
 *
 * Verification policy (research notes Section 2.2 — identical in pcontacts
 * and protoncore):
 *   - CLEAR_TEXT and ENCRYPTED carry no signature; verified = true
 *     (ENCRYPTED counts as verified-by-decryption under the user's own key).
 *   - SIGNED / ENCRYPTED_AND_SIGNED with a missing or failing signature keep
 *     their plaintext with verified = false — a downgrade-resistant warning
 *     beats data loss.
 *   - Unknown wire types, oversized blobs, and crypto failures become typed
 *     [CardFailure] entries: never a crash, never a silent drop.
 */
class ContactDecrypter(
    private val cryptoOp: (CardCryptoRequest) -> CardCryptoOutcome,
) {

    fun decryptContact(cards: List<ContactCardDto>): ContactCardsResult {
        val accepted = cards.take(MAX_CARDS_PER_CONTACT)
        val overflow = cards.size - accepted.size

        val cardsOut = mutableListOf<DecryptedCard>()
        val failures = mutableListOf<CardFailure>()
        accepted.forEachIndexed { index, card ->
            when (val outcome = decryptOne(index, card)) {
                is CardOutcome.Ok -> cardsOut += outcome.card
                is CardOutcome.Dropped -> failures += outcome.failure
            }
        }
        repeat(overflow) { i ->
            failures += CardFailure(accepted.size + i, cards[accepted.size + i].type, CardFailureReason.TOO_MANY_CARDS)
        }

        failures.forEach { failure ->
            when (failure.reason) {
                CardFailureReason.DECRYPT_FAILED -> SafeLog.log(SafeLog.Event.CONTACT_CARD_DECRYPT_FAILED)
                else -> SafeLog.log(SafeLog.Event.CONTACT_CARD_SKIPPED, failure.wireType)
            }
        }
        return ContactCardsResult(cardsOut, failures)
    }

    private fun decryptOne(index: Int, card: ContactCardDto): CardOutcome {
        val type = CardType.fromWire(card.type)
            ?: return CardOutcome.Dropped(CardFailure(index, card.type, CardFailureReason.UNKNOWN_TYPE))
        if (card.data.isBlank()) {
            return CardOutcome.Dropped(CardFailure(index, card.type, CardFailureReason.EMPTY_CARD))
        }
        if (card.data.length > MAX_CARD_CHARS) {
            return CardOutcome.Dropped(CardFailure(index, card.type, CardFailureReason.CARD_TOO_LARGE))
        }
        return runCrypto(index, card, type)
    }

    private fun runCrypto(index: Int, card: ContactCardDto, type: CardType): CardOutcome {
        val outcome = try {
            dispatch(card, type)
        } catch (ignored: CardDecryptException) {
            return CardOutcome.Dropped(CardFailure(index, card.type, CardFailureReason.DECRYPT_FAILED))
        }
        if (outcome.plaintext.toByteArray(Charsets.UTF_8).size > MAX_PLAINTEXT_BYTES) {
            return CardOutcome.Dropped(CardFailure(index, card.type, CardFailureReason.PLAINTEXT_TOO_LARGE))
        }
        return CardOutcome.Ok(DecryptedCard(type, outcome.plaintext, outcome.verified))
    }

    private fun dispatch(card: ContactCardDto, type: CardType): CardCryptoOutcome = when (type) {
        CardType.CLEAR_TEXT -> CardCryptoOutcome(card.data, verified = true)

        CardType.ENCRYPTED ->
            cryptoOp(CardCryptoRequest.DecryptOnly(card.data)).copy(verified = true)

        CardType.SIGNED -> {
            val signature = card.signature
            if (signature.isNullOrBlank()) {
                // Server-side malformed; retain plaintext as unverified.
                CardCryptoOutcome(card.data, verified = false)
            } else {
                cryptoOp(CardCryptoRequest.VerifyOnly(card.data, signature))
            }
        }

        CardType.ENCRYPTED_AND_SIGNED -> {
            val signature = card.signature
            if (signature.isNullOrBlank()) {
                cryptoOp(CardCryptoRequest.DecryptOnly(card.data)).copy(verified = false)
            } else {
                cryptoOp(CardCryptoRequest.DecryptAndVerify(card.data, signature))
            }
        }
    }

    private sealed interface CardOutcome {
        data class Ok(val card: DecryptedCard) : CardOutcome
        data class Dropped(val failure: CardFailure) : CardOutcome
    }

    companion object {
        /**
         * Fail-closed size guards (plan Rule 5), not semantic limits: real
         * contacts carry 1–3 cards of at most a few hundred KB (inline
         * base64 photos). These caps stop a hostile/compromised server from
         * making the device allocate unbounded buffers mid-sync.
         */
        const val MAX_CARDS_PER_CONTACT = 32
        const val MAX_CARD_CHARS = 8 * 1024 * 1024
        const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
    }
}
