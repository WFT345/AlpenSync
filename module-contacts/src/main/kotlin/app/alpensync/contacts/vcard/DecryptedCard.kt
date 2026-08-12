// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-contacts/src/main/kotlin/io/pcontacts/core/protoncontacts/DecryptedCard.kt

package app.alpensync.contacts.vcard

/**
 * One Card after dispatch + crypto: the plaintext vCard fragment plus the
 * per-card verification verdict. [originalType] is preserved so the merger
 * can apply the "discard UID properties from non-SIGNED cards" rule.
 *
 * A card whose signature is missing or fails verification RETAINS its
 * plaintext with [verified] = false rather than being dropped — losing data
 * silently is worse than surfacing a downgrade indicator (research notes
 * Section 2.2; same policy in pcontacts and protoncore).
 */
data class DecryptedCard(
    val originalType: CardType,
    val plaintext: String,
    val verified: Boolean,
)
