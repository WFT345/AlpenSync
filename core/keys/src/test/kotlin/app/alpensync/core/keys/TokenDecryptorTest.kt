// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TokenDecryptor] round-trips: an armored OpenPGP message encrypted to a
 * test key must decrypt back to the exact plaintext with the matching
 * private key, and must fail closed ([TokenDecryptException]) for every
 * mismatch — wrong key set, malformed input, empty key list.
 */
class TokenDecryptorTest {

    @Test fun decrypt_round_trips_message_encrypted_to_subkey() {
        val ring = TestPgp.generateRing(EMPTY, withSubkey = true)
        val keys = KeyUnlock.unlock(TestPgp.armoredSecret(ring), EMPTY.copyOf()).allPrivateKeys
        val plaintext = "decafbad0123456789".toByteArray(Charsets.US_ASCII)

        val blob = TestPgp.encryptArmored(plaintext, TestPgp.encryptionPublicKey(ring))
        val decrypted = TokenDecryptor.decrypt(blob, keys)

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test fun decrypt_round_trips_message_encrypted_to_primary() {
        val ring = TestPgp.generateRing(EMPTY, withSubkey = false)
        val keys = KeyUnlock.unlock(TestPgp.armoredSecret(ring), EMPTY.copyOf()).allPrivateKeys
        val plaintext = "primary-key-target".toByteArray(Charsets.US_ASCII)

        val blob = TestPgp.encryptArmored(plaintext, ring.secretKey.publicKey)
        val decrypted = TokenDecryptor.decrypt(blob, keys)

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test fun decrypt_picks_the_matching_key_out_of_several() {
        val ringA = TestPgp.generateRing(EMPTY, withSubkey = false)
        val ringB = TestPgp.generateRing(EMPTY, withSubkey = false)
        val keysA = KeyUnlock.unlock(TestPgp.armoredSecret(ringA), EMPTY.copyOf()).allPrivateKeys
        val keysB = KeyUnlock.unlock(TestPgp.armoredSecret(ringB), EMPTY.copyOf()).allPrivateKeys
        val plaintext = "multi-key-select".toByteArray(Charsets.US_ASCII)

        val blob = TestPgp.encryptArmored(plaintext, ringB.secretKey.publicKey)
        val decrypted = TokenDecryptor.decrypt(blob, keysA + keysB)

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test(expected = TokenDecryptException::class)
    fun decrypt_with_unrelated_key_fails_closed() {
        val ring = TestPgp.generateRing(EMPTY, withSubkey = false)
        val otherKeys = KeyUnlock.unlock(
            TestPgp.armoredSecret(TestPgp.generateRing(EMPTY, withSubkey = false)),
            EMPTY.copyOf(),
        ).allPrivateKeys
        val blob = TestPgp.encryptArmored("secret".toByteArray(Charsets.US_ASCII), ring.secretKey.publicKey)

        TokenDecryptor.decrypt(blob, otherKeys)
    }

    @Test(expected = TokenDecryptException::class)
    fun decrypt_malformed_message_fails_closed() {
        val ring = TestPgp.generateRing(EMPTY, withSubkey = false)
        val keys = KeyUnlock.unlock(TestPgp.armoredSecret(ring), EMPTY.copyOf()).allPrivateKeys

        TokenDecryptor.decrypt("-----BEGIN PGP MESSAGE-----\n\nbm90LW1lc3NhZ2U=\n-----END PGP MESSAGE-----", keys)
    }

    @Test fun decrypt_rejects_empty_key_list() {
        val ring = TestPgp.generateRing(EMPTY, withSubkey = false)
        val blob = TestPgp.encryptArmored("secret".toByteArray(Charsets.US_ASCII), ring.secretKey.publicKey)

        val ex = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TokenDecryptor.decrypt(blob, emptyList())
        }
        assertEquals("at least one decryption key required", ex.message)
    }

    private companion object {
        val EMPTY: CharArray = CharArray(0)
    }
}
