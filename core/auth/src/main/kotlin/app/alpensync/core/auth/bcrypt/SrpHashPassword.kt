// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/main/kotlin/io/pcontacts/core/crypto/bcrypt/SrpHashPassword.kt

package app.alpensync.core.auth.bcrypt

import app.alpensync.core.auth.util.expandHash
import java.util.Base64
import org.bouncycastle.crypto.generators.OpenBSDBCrypt

/**
 * Proton SRP `hashPassword` — derives the 256-byte value used as the SRP
 * `x` parameter. Live-verified by pcontacts against go-srp
 * `hashPasswordVersion3` (versions 3/4 both reduce to this construction):
 *
 *   1. `rawSalt = base64Decode(saltB64)` — decode salt to raw bytes.
 *   2. `saltWithSuffix = rawSalt ‖ bytes("proton")` — append ASCII "proton".
 *   3. `bcryptSalt = first16(saltWithSuffix)`.
 *   4. `unexpandedHash = bcrypt(password, cost=10, bcryptSalt)` → the full
 *      60-character "$2y$10$…" string.
 *   5. `hashBytes = per-char code-point bytes of unexpandedHash` (pure ASCII).
 *   6. `expandHash(hashBytes ‖ modulusWireBytes)` → 256 bytes.
 *
 * Critical: the salt is base64-DECODED before appending "proton" — using
 * the ASCII bytes of the base64 string itself yields `auth_failed`
 * (pcontacts confirmed by live testing).
 *
 * This is NOT the same as [ComputeKeyPassword.derive] — that function
 * implements the key-unlock derivation, which uses a different salt source
 * (keys/salts), no "proton" suffix, no expandHash, and keeps only the
 * 31-char trailing hash. Conflating the two is the classic bug.
 */
object SrpHashPassword {

    private const val COST: Int = 10
    private const val BCRYPT_SALT_BYTES: Int = 16

    /**
     * @param password  user's plaintext password.
     * @param saltB64   base64 salt from `auth/info`.
     * @param modulusBytes  decoded SRP modulus (N) bytes in wire order
     *                      (little-endian, exactly as received).
     * @return 256-byte expanded hash; SRP `x` is `fromLittleEndian(result)`.
     */
    fun derive(password: CharArray, saltB64: String, modulusBytes: ByteArray): ByteArray {
        val rawSalt = Base64.getDecoder().decode(saltB64)
        val saltWithSuffix = rawSalt + "proton".toByteArray(Charsets.US_ASCII)
        val bcryptSalt = saltWithSuffix.copyOfRange(0, minOf(saltWithSuffix.size, BCRYPT_SALT_BYTES))
        require(bcryptSalt.size == BCRYPT_SALT_BYTES) {
            "salt + 'proton' must yield at least $BCRYPT_SALT_BYTES bytes"
        }

        val unexpandedHash = OpenBSDBCrypt.generate(password, bcryptSalt, COST)
        val hashBytes = charCodeBytes(unexpandedHash)

        val concat = ByteArray(hashBytes.size + modulusBytes.size)
        System.arraycopy(hashBytes, 0, concat, 0, hashBytes.size)
        System.arraycopy(modulusBytes, 0, concat, hashBytes.size, modulusBytes.size)

        return expandHash(concat)
    }

    /**
     * Each character's code point as a byte — matches the JS
     * `binaryStringToUint8Array` helper. bcrypt output is pure ASCII so no
     * char exceeds 0x7F.
     */
    private fun charCodeBytes(s: String): ByteArray {
        val out = ByteArray(s.length)
        for (i in s.indices) {
            out[i] = s[i].code.toByte()
        }
        return out
    }
}
