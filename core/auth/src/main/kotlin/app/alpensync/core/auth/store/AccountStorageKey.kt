// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// New at the 2026-09 audit (finding L2): the previous per-store sanitize
// (filter out non-[letter/digit/-/_] characters) was collision-prone —
// "a@b.c" and "abc" both mapped to "abc", so two distinct accounts could
// share one prefs file AND one Keystore KEK alias, silently overwriting
// each other's secrets. Two independent copies of that sanitize existed
// (EncryptedSecretStore and CanonicalVCardStore); both now derive their
// namespaces here. Pre-release change: 0.1.0 has not shipped, so there is
// no on-device data to migrate.

package app.alpensync.core.auth.store

import java.security.MessageDigest

/**
 * The single account-id → storage-namespace derivation for every
 * per-account artifact the app persists: the encrypted-prefs filename and
 * KEK alias ([EncryptedSecretStore]) and the canonical-vCard KEK alias
 * (:module-contacts' CanonicalVCardStore).
 *
 * SHA-256 of the raw UTF-8 id, lowercase hex:
 *   - collision-free in practice — distinct ids can never share a
 *     namespace, unlike the character-stripping it replaces;
 *   - a closed [0-9a-f] alphabet, so no id — hostile or not — can escape
 *     the file/alias namespace (the old sanitize's original job);
 *   - fixed 64-char length, so the prefs filename stays valid however
 *     long the account id is.
 */
object AccountStorageKey {

    fun of(accountId: String): String {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val v = byte.toInt() and BYTE_MASK
                append(HEX[v ushr NIBBLE_BITS])
                append(HEX[v and NIBBLE_MASK])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_MASK = 0x0F
    private const val NIBBLE_BITS = 4
}
