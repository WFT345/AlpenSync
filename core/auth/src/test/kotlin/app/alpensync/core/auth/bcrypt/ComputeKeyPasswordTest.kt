// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/test/kotlin/io/pcontacts/core/crypto/bcrypt/ComputeKeyPasswordTest.kt
// Deviation (2026-09 audit, finding L1): derive returns zeroable ASCII
// bytes instead of a String, so the assertions compare byte arrays — and
// the equivalence block pins the hand-rolled radix-64/NUL-termination path
// byte-for-byte against BouncyCastle's OpenBSDBCrypt, the implementation
// it replaced.

package app.alpensync.core.auth.bcrypt

import java.util.Base64
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ComputeKeyPasswordTest {

    private val saltB64: String = Base64.getEncoder().encodeToString(ByteArray(16) { i -> i.toByte() })

    @Test fun derives_31_byte_trailing_hash() {
        val result = ComputeKeyPassword.derive("hunter2".toCharArray(), saltB64)
        assertEquals("trailing hash must be 31 bytes", 31, result.size)
    }

    @Test fun deterministic_for_same_inputs() {
        val a = ComputeKeyPassword.derive("p4ssword".toCharArray(), saltB64)
        val b = ComputeKeyPassword.derive("p4ssword".toCharArray(), saltB64)
        assertArrayEquals(a, b)
    }

    @Test fun differs_on_different_passwords() {
        val a = ComputeKeyPassword.derive("alice".toCharArray(), saltB64)
        val b = ComputeKeyPassword.derive("bob".toCharArray(), saltB64)
        assertFalse(a.contentEquals(b))
    }

    @Test fun differs_on_different_salts() {
        val saltA = Base64.getEncoder().encodeToString(ByteArray(16) { 0x11 })
        val saltB = Base64.getEncoder().encodeToString(ByteArray(16) { 0x22 })
        val a = ComputeKeyPassword.derive("samepassword".toCharArray(), saltA)
        val b = ComputeKeyPassword.derive("samepassword".toCharArray(), saltB)
        assertFalse(a.contentEquals(b))
    }

    @Test fun unicode_password_does_not_crash() {
        val out = ComputeKeyPassword.derive("p4sséèà".toCharArray(), saltB64)
        assertEquals(31, out.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_wrong_salt_size() {
        val shortSalt = Base64.getEncoder().encodeToString(ByteArray(8))
        ComputeKeyPassword.derive("x".toCharArray(), shortSalt)
    }

    // ------------------------------------------------------------------
    // Equivalence with the OpenBSDBCrypt path this implementation replaced
    // (String output made the passphrase unzeroable — audit finding L1).
    // Any drift in the NUL-termination, 72-byte cap, 23-byte truncation,
    // or radix-64 encoding fails here before it could ever reach a login.
    // ------------------------------------------------------------------

    @Test fun matches_OpenBSDBCrypt_trailing_hash_across_password_shapes() {
        val passwords = listOf(
            "hunter2",
            "p4sséèà-ünïcøde-密码",
            "a", // single byte
            "x".repeat(71), // one under the bcrypt cap
            "y".repeat(72), // exactly at the cap (no NUL appended)
            "z".repeat(100), // over the cap (truncated to 72)
        )
        for (password in passwords) {
            assertEquals(
                "trailing-hash mismatch for password shape len=${password.length}",
                openBsdTrailingHash(password, saltB64),
                String(ComputeKeyPassword.derive(password.toCharArray(), saltB64), Charsets.US_ASCII),
            )
        }
    }

    @Test fun matches_OpenBSDBCrypt_across_salts() {
        val salts = listOf(
            ByteArray(16), // all zero
            ByteArray(16) { 0xFF.toByte() }, // all ones
            ByteArray(16) { i -> (i * 17).toByte() },
        )
        for (salt in salts) {
            val b64 = Base64.getEncoder().encodeToString(salt)
            assertEquals(
                "trailing-hash mismatch for salt $b64",
                openBsdTrailingHash("correct horse battery staple", b64),
                String(ComputeKeyPassword.derive("correct horse battery staple".toCharArray(), b64), Charsets.US_ASCII),
            )
        }
    }

    /** The previous implementation, verbatim: full OpenBSD string, first 29 chars stripped. */
    private fun openBsdTrailingHash(password: String, saltB64: String): String {
        val salt = Base64.getDecoder().decode(saltB64)
        return OpenBSDBCrypt.generate(password.toCharArray(), salt, 10).substring(29)
    }
}
