// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/test/kotlin/io/pcontacts/core/crypto/srp/SrpClientTest.kt

package app.alpensync.core.auth.srp

import app.alpensync.core.auth.util.toUnsignedBigInteger
import app.alpensync.core.auth.util.toUnsignedBytes
import java.math.BigInteger
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrpClientTest {

    /**
     * RFC 3526 §2 — 1024-bit MODP group (the smallest standard SRP group).
     * Useful for fast tests; production uses the 2048-bit Proton modulus.
     */
    private val n1024 = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
            "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
            "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
            "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
            "49286651ECE65381FFFFFFFFFFFFFFFF",
        16,
    )

    private val g2 = BigInteger.valueOf(2)

    private fun seededClient(seed: Byte) =
        SrpClient(random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(seed)) })

    @Test fun login_produces_consistent_self_round_trip() {
        val b = BigInteger("11" + "0".repeat(60), 16)
        val x = BigInteger("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 16)

        val first = seededClient(1).login(N = n1024, g = g2, serverEphemeralB = b, x = x)
        val second = seededClient(1).login(N = n1024, g = g2, serverEphemeralB = b, x = x)

        assertEquals(first.clientEphemeralA, second.clientEphemeralA)
        assertArrayEquals(first.clientProofM1, second.clientProofM1)
        assertArrayEquals(first.expectedServerProofM2, second.expectedServerProofM2)
        assertArrayEquals(first.sharedSessionKey, second.sharedSessionKey)
    }

    @Test fun different_password_x_yields_different_proof_when_random_seeded_identically() {
        val b = BigInteger("22" + "0".repeat(60), 16)
        val proofA = seededClient(9).login(N = n1024, g = g2, serverEphemeralB = b, x = BigInteger("aaaaaaaa", 16))
        val proofB = seededClient(9).login(N = n1024, g = g2, serverEphemeralB = b, x = BigInteger("bbbbbbbb", 16))

        assertEquals("A should be identical when a is identical", proofA.clientEphemeralA, proofB.clientEphemeralA)
        assertFalse("M1 must differ when x differs", proofA.clientProofM1.contentEquals(proofB.clientProofM1))
        assertFalse("session key must differ when x differs",
            proofA.sharedSessionKey.contentEquals(proofB.sharedSessionKey))
        assertFalse("expected M2 must differ when x differs",
            proofA.expectedServerProofM2.contentEquals(proofB.expectedServerProofM2))
    }

    @Test fun A_is_in_correct_padded_form_for_modulus() {
        val proof = SrpClient().login(
            N = n1024, g = g2,
            serverEphemeralB = BigInteger("ff" + "0".repeat(60), 16),
            x = BigInteger("1234", 16),
        )
        val padLen = (n1024.bitLength() + 7) / 8
        val encoded = proof.clientEphemeralA.toUnsignedBytes(padLen)
        assertEquals(padLen, encoded.size)
        assertEquals(proof.clientEphemeralA, encoded.toUnsignedBigInteger())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_B_that_is_zero_mod_N() {
        SrpClient().login(N = n1024, g = g2, serverEphemeralB = n1024, x = BigInteger.ONE)
    }

    @Test fun verifies_matching_server_proof() {
        val client = SrpClient()
        val proof = client.login(
            N = n1024, g = g2,
            serverEphemeralB = BigInteger("aa" + "0".repeat(60), 16),
            x = BigInteger("5", 16),
        )
        assertTrue(client.verifyServerProof(proof.expectedServerProofM2, proof.expectedServerProofM2))
    }

    @Test fun rejects_mismatched_server_proof() {
        val client = SrpClient()
        val proof = client.login(
            N = n1024, g = g2,
            serverEphemeralB = BigInteger("aa" + "0".repeat(60), 16),
            x = BigInteger("5", 16),
        )
        val tampered = proof.expectedServerProofM2.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(client.verifyServerProof(tampered, proof.expectedServerProofM2))
    }

    @Test fun rejects_size_mismatched_server_proof() {
        val client = SrpClient()
        val proof = client.login(
            N = n1024, g = g2,
            serverEphemeralB = BigInteger("aa" + "0".repeat(60), 16),
            x = BigInteger("5", 16),
        )
        assertFalse(client.verifyServerProof(byteArrayOf(0, 1, 2), proof.expectedServerProofM2))
    }

    @Test fun client_proof_is_256_bytes_from_expand_hash() {
        val proof = SrpClient().login(
            N = n1024, g = g2,
            serverEphemeralB = BigInteger("aa" + "0".repeat(60), 16),
            x = BigInteger("5", 16),
        )
        assertEquals("M1 must be 256 bytes (4× SHA-512)", 256, proof.clientProofM1.size)
        assertEquals("M2 must be 256 bytes (4× SHA-512)", 256, proof.expectedServerProofM2.size)
    }
}
