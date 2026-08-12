// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/main/kotlin/io/pcontacts/core/crypto/util/Bytes.kt

package app.alpensync.core.auth.util

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Returns `value` as an unsigned big-endian byte array of exactly
 * [lengthBytes]. SRP requires fixed-width inputs to its hash mixing
 * functions ("PAD" in RFC 5054 §3); this is that primitive.
 */
internal fun BigInteger.toUnsignedBytes(lengthBytes: Int): ByteArray {
    val raw = this.toByteArray()
    // BigInteger.toByteArray returns the minimum number of bytes in two's
    // complement, sometimes prefixed with 0x00 to signify sign. Strip the
    // leading sign byte if present, then left-pad with zeros.
    val stripped = if (raw.isNotEmpty() && raw[0] == 0.toByte() && raw.size > 1) {
        raw.copyOfRange(1, raw.size)
    } else {
        raw
    }
    require(stripped.size <= lengthBytes) {
        "BigInteger does not fit in $lengthBytes bytes (was ${stripped.size})"
    }
    if (stripped.size == lengthBytes) return stripped
    val padded = ByteArray(lengthBytes)
    System.arraycopy(stripped, 0, padded, lengthBytes - stripped.size, stripped.size)
    return padded
}

internal fun ByteArray.toUnsignedBigInteger(): BigInteger = BigInteger(1, this)

/**
 * Proton's `expandHash` — 4× SHA-512 with a one-byte counter appended,
 * yielding 256 bytes. Used by both `hashPassword` (SRP x derivation) and
 * the SRP protocol itself (k, u, M1, M2). Live-verified by pcontacts
 * against go-srp `hash.go:expandHash` and `@protontech/crypto`
 * `src/srp/passwords.ts:expandHash`.
 */
internal fun expandHash(input: ByteArray): ByteArray {
    val result = ByteArray(EXPAND_BLOCKS * SHA512_BYTES)
    for (i in 0 until EXPAND_BLOCKS) {
        val md = MessageDigest.getInstance("SHA-512")
        md.update(input)
        md.update(i.toByte())
        System.arraycopy(md.digest(), 0, result, i * SHA512_BYTES, SHA512_BYTES)
    }
    return result
}

private const val EXPAND_BLOCKS = 4
private const val SHA512_BYTES = 64

/**
 * Converts a BigInteger to a little-endian byte array of exactly
 * [lengthBytes], matching go-srp's `fromNat(bitLength, nat)`.
 */
internal fun BigInteger.toLittleEndianBytes(lengthBytes: Int): ByteArray =
    toUnsignedBytes(lengthBytes).reversedArray()

/**
 * Interprets a little-endian byte array as an unsigned BigInteger,
 * matching go-srp's `toNat(buf)`.
 */
internal fun ByteArray.fromLittleEndianBigInteger(): BigInteger =
    BigInteger(1, this.reversedArray())
