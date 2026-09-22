// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/main/kotlin/io/pcontacts/core/crypto/bcrypt/ComputeKeyPassword.kt
// Deviation (2026-09 audit, finding L1): pcontacts returns the passphrase
// as a String via OpenBSDBCrypt.generate. An immutable String can never be
// zeroed, so this port drives BouncyCastle's raw BCrypt.generate and does
// the OpenBSD radix-64 encoding itself, returning a caller-zeroable
// ByteArray with every intermediate wiped. Byte-for-byte equivalence with
// the OpenBSDBCrypt path is pinned by ComputeKeyPasswordTest (equivalence
// cases) and CapturedVectorsTest (@protontech/crypto vectors).

package app.alpensync.core.auth.bcrypt

import java.util.Base64
import org.bouncycastle.crypto.generators.BCrypt
import org.bouncycastle.util.Strings

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

    /** OpenBSDBCrypt caps passwords at 72 bytes; shorter ones gain a NUL. */
    private const val MAX_PASSWORD_BYTES: Int = 72

    /** OpenBSD bcrypt encodes 23 of BCrypt.generate's 24 output bytes... */
    private const val HASH_BYTES_ENCODED: Int = 23

    /** ...into ceil(23 * 8 / 6) = 31 radix-64 characters. */
    private const val TRAILING_HASH_LENGTH: Int = 31

    private const val BYTE_MASK: Int = 0xFF
    private const val SIX_BITS: Int = 0x3F

    /** OpenBSD bcrypt's radix-64 alphabet (OpenBSDBCrypt's encodingTable). */
    private val radix64Alphabet: ByteArray =
        "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            .toByteArray(Charsets.US_ASCII)

    /**
     * @param password user's plaintext mailbox password.
     * @param keySaltB64 base64-encoded 16-byte salt from `KeySalts[i].KeySalt`.
     * @return the 31-character trailing hash portion of the bcrypt output —
     *         the passphrase that unlocks the PGP private keys — as ASCII
     *         bytes. The caller owns the array and MUST zero it when done
     *         (Rule 1); every intermediate buffer is zeroed here.
     */
    fun derive(password: CharArray, keySaltB64: String): ByteArray {
        val raw = Base64.getDecoder().decode(keySaltB64)
        require(raw.size == BCRYPT_SALT_BYTES) {
            "KeySalt must decode to $BCRYPT_SALT_BYTES bytes, was ${raw.size}"
        }
        val passwordBytes = nullTerminatedUtf8(password)
        val hash = try {
            BCrypt.generate(passwordBytes, raw, COST)
        } finally {
            passwordBytes.fill(0)
        }
        return try {
            encodeTrailingHash(hash)
        } finally {
            hash.fill(0)
        }
    }

    /**
     * The password bytes exactly as OpenBSDBCrypt prepares them before
     * calling [BCrypt.generate]: UTF-8, then either truncated to 72 bytes
     * or extended by one NUL terminator.
     */
    private fun nullTerminatedUtf8(password: CharArray): ByteArray {
        val utf8 = Strings.toUTF8ByteArray(password)
        val prepared = if (utf8.size >= MAX_PASSWORD_BYTES) {
            utf8.copyOf(MAX_PASSWORD_BYTES)
        } else {
            utf8.copyOf(utf8.size + 1) // copyOf zero-fills the added NUL
        }
        utf8.fill(0)
        return prepared
    }

    /**
     * OpenBSDBCrypt's encodeData over the first 23 hash bytes: seven full
     * 3-byte→4-char groups, then the 2-byte→3-char tail (its `len % 3 == 2`
     * case). Returns the 31 ASCII bytes of the trailing hash.
     */
    private fun encodeTrailingHash(hash: ByteArray): ByteArray {
        val out = ByteArray(TRAILING_HASH_LENGTH)
        var o = 0
        var i = 0
        while (i + 2 < HASH_BYTES_ENCODED) {
            val a1 = hash[i].toInt() and BYTE_MASK
            val a2 = hash[i + 1].toInt() and BYTE_MASK
            val a3 = hash[i + 2].toInt() and BYTE_MASK
            out[o++] = radix64Alphabet[(a1 ushr 2) and SIX_BITS]
            out[o++] = radix64Alphabet[((a1 shl 4) or (a2 ushr 4)) and SIX_BITS]
            out[o++] = radix64Alphabet[((a2 shl 2) or (a3 ushr 6)) and SIX_BITS]
            out[o++] = radix64Alphabet[a3 and SIX_BITS]
            i += 3
        }
        val a1 = hash[i].toInt() and BYTE_MASK
        val a2 = hash[i + 1].toInt() and BYTE_MASK
        out[o++] = radix64Alphabet[(a1 ushr 2) and SIX_BITS]
        out[o++] = radix64Alphabet[((a1 shl 4) or (a2 ushr 4)) and SIX_BITS]
        out[o] = radix64Alphabet[(a2 shl 2) and SIX_BITS]
        return out
    }
}
