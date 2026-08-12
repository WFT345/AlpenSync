// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/sync/src/main/kotlin/io/pcontacts/core/sync/auth/SrpXDerivation.kt

package app.alpensync.core.auth

import app.alpensync.core.auth.bcrypt.SrpHashPassword
import java.math.BigInteger

/**
 * SRP `x` derivation for Proton's SRP variant (go-srp versions 3/4).
 *
 * The `hashPassword` output is a 256-byte expandHash result; go-srp
 * interprets it as little-endian via `toNat()` — reverse before
 * constructing the BigInteger.
 */
object SrpXDerivation {

    /**
     * @param password     user's plaintext password.
     * @param srpSaltB64   base64 salt from `auth/info`.
     * @param modulusBytes raw SRP modulus bytes as received from the API
     *                     (little-endian wire format, not reversed).
     * @return SRP `x` = fromLittleEndian(hashPassword(password, salt, modulus)).
     */
    fun deriveX(password: CharArray, srpSaltB64: String, modulusBytes: ByteArray): BigInteger {
        val expanded = SrpHashPassword.derive(password, srpSaltB64, modulusBytes)
        return BigInteger(1, expanded.reversedArray())
    }
}
