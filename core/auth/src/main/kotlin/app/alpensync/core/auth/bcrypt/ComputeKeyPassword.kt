// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/main/kotlin/io/pcontacts/core/crypto/bcrypt/ComputeKeyPassword.kt

package app.alpensync.core.auth.bcrypt

import java.util.Base64
import org.bouncycastle.crypto.generators.OpenBSDBCrypt

/**
 * Derives the mailbox key-password from the user's plaintext password and
 * a per-key salt (`GET core/v4/keys/salts`).
 *
 * Algorithm — `bcrypt(password, salt, cost=10)` then strip the first 29
 * characters ("$2y$10$" prefix + 22-char encoded salt), returning only the
 * 31-character trailing hash. Matches `@protontech/crypto`
 * `src/srp/keys.ts:computeKeyPassword` exactly (verified by pcontacts
 * against the JS source + captured vectors + a live key unlock).
 *
 * This is a DIFFERENT bcrypt call from [SrpHashPassword.derive] (SRP x):
 * different salt source, no "proton" suffix, no expandHash, and only the
 * trailing hash is kept. See the comparison table in
 * docs/research/m1-auth-api-notes.md Section 5.2.
 */
object ComputeKeyPassword {

    private const val COST: Int = 10
    private const val BCRYPT_SALT_BYTES: Int = 16
    private const val BCRYPT_PREFIX_LEN: Int = 29 // "$2y$10$" (7) + encoded-salt (22)

    /**
     * @param password user's plaintext mailbox password.
     * @param keySaltB64 base64-encoded 16-byte salt from `KeySalts[i].KeySalt`.
     * @return the 31-character trailing hash portion of the bcrypt output —
     *         the passphrase that unlocks the PGP private keys.
     */
    fun derive(password: CharArray, keySaltB64: String): String {
        val raw = Base64.getDecoder().decode(keySaltB64)
        require(raw.size == BCRYPT_SALT_BYTES) {
            "KeySalt must decode to $BCRYPT_SALT_BYTES bytes, was ${raw.size}"
        }
        val full = OpenBSDBCrypt.generate(password, raw, COST)
        return full.substring(BCRYPT_PREFIX_LEN)
    }
}
