// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyUnlock] against keys generated in-test: the happy path must unlock
 * EVERY secret key in the ring (primary + encryption subkey — real Proton
 * rings are split), and every failure mode must throw [KeyUnlockException]
 * (fail-closed — a wrong passphrase must never yield partial key material).
 */
class KeyUnlockTest {

    @Test fun correct_passphrase_unlocks_primary_and_encryption_subkey() {
        val armored = TestPgp.armoredSecret(TestPgp.generateRing(PASSPHRASE.copyOf(), withSubkey = true))

        val unlocked = KeyUnlock.unlock(armored, PASSPHRASE.copyOf())

        assertEquals("primary + subkey", 2, unlocked.allPrivateKeys.size)
        assertEquals("primary handle is the ring master", unlocked.public.raw.keyID, unlocked.primary.raw.keyID)
        val subkey = unlocked.allPrivateKeys.first { it.raw.keyID != unlocked.primary.raw.keyID }
        assertNotEquals(unlocked.primary.raw.keyID, subkey.raw.keyID)
    }

    @Test fun ring_without_subkey_unlocks_single_key() {
        val armored = TestPgp.armoredSecret(TestPgp.generateRing(PASSPHRASE.copyOf(), withSubkey = false))

        val unlocked = KeyUnlock.unlock(armored, PASSPHRASE.copyOf())

        assertEquals(1, unlocked.allPrivateKeys.size)
    }

    @Test(expected = KeyUnlockException::class)
    fun wrong_passphrase_fails_closed() {
        val armored = TestPgp.armoredSecret(TestPgp.generateRing(PASSPHRASE.copyOf(), withSubkey = true))

        KeyUnlock.unlock(armored, "definitely-not-the-passphrase".toCharArray())
    }

    @Test(expected = KeyUnlockException::class)
    fun malformed_armor_fails_closed() {
        KeyUnlock.unlock(
            "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nbm90LWEta2V5\n-----END PGP PRIVATE KEY BLOCK-----",
            PASSPHRASE.copyOf(),
        )
    }

    @Test(expected = KeyUnlockException::class)
    fun non_pgp_input_fails_closed() {
        KeyUnlock.unlock("this is not a pgp block at all", PASSPHRASE.copyOf())
    }

    @Test fun unlocked_key_material_is_functional_not_just_present() {
        // Decrypt a real message with the unlocked subkey — proves the
        // extracted private keys are usable crypto material, not stubs.
        val ring = TestPgp.generateRing(PASSPHRASE.copyOf(), withSubkey = true)
        val unlocked = KeyUnlock.unlock(TestPgp.armoredSecret(ring), PASSPHRASE.copyOf())
        val secret = "key-unlock-round-trip".toByteArray(Charsets.US_ASCII)

        val blob = TestPgp.encryptArmored(secret, TestPgp.encryptionPublicKey(ring))
        val roundTrip = TokenDecryptor.decrypt(blob, unlocked.allPrivateKeys)

        assertTrue(secret.contentEquals(roundTrip))
    }

    private companion object {
        val PASSPHRASE: CharArray = "test-passphrase-123".toCharArray()
    }
}
