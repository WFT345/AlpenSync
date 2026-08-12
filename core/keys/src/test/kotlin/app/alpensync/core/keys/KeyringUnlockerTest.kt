// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.keys

import app.alpensync.core.api.dto.AddressDto
import app.alpensync.core.api.dto.AddressKeyDto
import app.alpensync.core.api.dto.GetAddressesResponse
import app.alpensync.core.api.dto.GetKeySaltsResponse
import app.alpensync.core.api.dto.GetUserResponse
import app.alpensync.core.api.dto.KeySaltDto
import app.alpensync.core.api.dto.UserDto
import app.alpensync.core.api.dto.UserKeyDto
import app.alpensync.core.auth.bcrypt.ComputeKeyPassword
import java.util.Base64
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyringUnlocker] end-to-end against in-test keys: bcrypt keyPassword
 * derivation from a salt DTO, user-ring unlock (primary + encryption
 * subkey), address-key unlock via the Token blob, the legacy no-Token
 * fallback, and the fail-closed paths (wrong password, missing salt,
 * missing primary key, undecryptable token → skip, never crash).
 *
 * The fixture mirrors the production wiring: the user ring's passphrase IS
 * the bcrypt-derived keyPassword, and the address ring's passphrase is the
 * plaintext of the Token blob encrypted to the user key — exactly what
 * `core/v4/users` + `core/v4/addresses` hand us (research notes Section 5).
 */
class KeyringUnlockerTest {

    @Test fun deriveKeyPassword_matches_the_bcrypt_recipe() {
        val derived = KeyringUnlocker.deriveKeyPassword(fixture.password.copyOf(), fixture.user, fixture.salts)

        assertEquals(fixture.keyPassword, String(derived, Charsets.UTF_8))
    }

    @Test fun unlockAll_unlocks_user_ring_and_address_key_via_token() {
        val keyPasswordBytes = KeyringUnlocker.deriveKeyPassword(
            fixture.password.copyOf(), fixture.user, fixture.salts,
        )

        val set = KeyringUnlocker.unlockAll(keyPasswordBytes, fixture.user, fixture.addresses)

        // user ring: primary + encryption subkey; address ring: single key.
        assertEquals(3, set.decryptionKeys.size)
        assertEquals(2, set.verificationKeys.size)

        // The unlocked address key must be functional material: a blob
        // encrypted to the address key decrypts through the returned set.
        val payload = "contacts-are-encrypted-to-address-keys".toByteArray(Charsets.US_ASCII)
        val blob = TestPgp.encryptArmored(payload, fixture.addressRing.secretKey.publicKey)
        val roundTrip = TokenDecryptor.decrypt(blob, set.decryptionKeys)
        assertTrue(payload.contentEquals(roundTrip))
    }

    @Test fun unlockAll_zeroes_the_key_password_buffer() {
        // Plan Rule 2: unlocked key material lives for the shortest window;
        // the keyPassword buffer is wiped inside unlockAll.
        val keyPasswordBytes = KeyringUnlocker.deriveKeyPassword(
            fixture.password.copyOf(), fixture.user, fixture.salts,
        )

        KeyringUnlocker.unlockAll(keyPasswordBytes, fixture.user, fixture.addresses)

        assertTrue("keyPassword buffer must be zeroed", keyPasswordBytes.all { it == 0.toByte() })
    }

    @Test fun wrong_password_aborts_with_typed_error() {
        val wrongDerived = KeyringUnlocker.deriveKeyPassword(
            "not-the-password".toCharArray(), fixture.user, fixture.salts,
        )

        assertThrows(KeyringUnlockException::class.java) {
            KeyringUnlocker.unlockAll(wrongDerived, fixture.user, fixture.addresses)
        }
    }

    @Test fun missing_active_primary_key_aborts() {
        val noPrimary = fixture.user.copy(
            user = fixture.user.user.copy(
                keys = fixture.user.user.keys.map { it.copy(primary = 0) },
            ),
        )

        assertThrows(KeyringUnlockException::class.java) {
            KeyringUnlocker.deriveKeyPassword(fixture.password.copyOf(), noPrimary, fixture.salts)
        }
    }

    @Test fun missing_key_salt_aborts() {
        val noSalt = GetKeySaltsResponse(keySalts = emptyList())

        assertThrows(KeyringUnlockException::class.java) {
            KeyringUnlocker.deriveKeyPassword(fixture.password.copyOf(), fixture.user, noSalt)
        }
    }

    @Test fun legacy_address_key_without_token_unlocks_via_user_key_password() {
        // UNVERIFIED legacy path (research notes Section 5.3): v1 address
        // keys carry no Token and unlock with the user keyPassword itself.
        val legacyRing = TestPgp.generateRing(
            fixture.keyPassword.toCharArray(), withSubkey = false, identity = "legacy@alpensync.test",
        )
        val legacyAddresses = GetAddressesResponse(
            addresses = listOf(
                AddressDto(
                    id = "legacy-addr",
                    keys = listOf(
                        AddressKeyDto(
                            id = "legacy-key",
                            privateKey = TestPgp.armoredSecret(legacyRing),
                            token = null,
                        ),
                    ),
                ),
            ),
        )
        val keyPasswordBytes = KeyringUnlocker.deriveKeyPassword(
            fixture.password.copyOf(), fixture.user, fixture.salts,
        )

        val set = KeyringUnlocker.unlockAll(keyPasswordBytes, fixture.user, legacyAddresses)

        assertEquals("user ring (2) + legacy address key (1)", 3, set.decryptionKeys.size)
    }

    @Test fun address_key_with_undecryptable_token_is_skipped_not_fatal() {
        // The Token blob is encrypted to an UNRELATED key, so decryption
        // fails: the address key is skipped and the user keys still unlock.
        val unrelatedRing = TestPgp.generateRing(CharArray(0), withSubkey = false)
        val badBlob = TestPgp.encryptArmored(
            "garbage-token".toByteArray(Charsets.US_ASCII), unrelatedRing.secretKey.publicKey,
        )
        val brokenAddresses = GetAddressesResponse(
            addresses = listOf(
                AddressDto(
                    id = "addr-broken",
                    keys = listOf(
                        AddressKeyDto(
                            id = "addr-key-broken",
                            privateKey = TestPgp.armoredSecret(fixture.addressRing),
                            token = badBlob,
                        ),
                    ),
                ),
            ),
        )
        val keyPasswordBytes = KeyringUnlocker.deriveKeyPassword(
            fixture.password.copyOf(), fixture.user, fixture.salts,
        )

        val set = KeyringUnlocker.unlockAll(keyPasswordBytes, fixture.user, brokenAddresses)

        assertEquals("only the user ring unlocks", 2, set.decryptionKeys.size)
    }

    private class Fixture {
        val password: CharArray = "correct horse battery staple".toCharArray()
        val saltB64: String = Base64.getEncoder().encodeToString(TEST_SALT)

        // The bcrypt-derived mailbox keyPassword — used as the user ring's
        // passphrase so the fixture matches production wiring.
        val keyPassword: String = ComputeKeyPassword.derive(password.copyOf(), saltB64)

        val userRing: PGPSecretKeyRing = TestPgp.generateRing(
            keyPassword.toCharArray(), withSubkey = true, identity = "user@alpensync.test",
        )
        val addressRing: PGPSecretKeyRing = TestPgp.generateRing(
            ADDRESS_TOKEN.toCharArray(), withSubkey = false, identity = "addr@alpensync.test",
        )

        val user = GetUserResponse(
            user = UserDto(
                id = "user-1",
                keys = listOf(
                    UserKeyDto(
                        id = USER_KEY_ID,
                        primary = 1,
                        active = 1,
                        privateKey = TestPgp.armoredSecret(userRing),
                    ),
                ),
            ),
        )
        val salts = GetKeySaltsResponse(
            keySalts = listOf(KeySaltDto(keyId = USER_KEY_ID, keySalt = saltB64)),
        )
        val addresses = GetAddressesResponse(
            addresses = listOf(
                AddressDto(
                    id = "addr-1",
                    keys = listOf(
                        AddressKeyDto(
                            id = "addr-key-1",
                            privateKey = TestPgp.armoredSecret(addressRing),
                            token = TestPgp.encryptArmored(
                                ADDRESS_TOKEN.toByteArray(Charsets.US_ASCII),
                                TestPgp.encryptionPublicKey(userRing),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val USER_KEY_ID = "user-key-1"
        const val ADDRESS_TOKEN = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        val TEST_SALT: ByteArray = ByteArray(16) { (it * 31 + 7).toByte() }

        // Generated once for the whole class: bcrypt cost 10 plus three RSA
        // keygens would dominate per-test setup otherwise.
        val fixture: Fixture by lazy { Fixture() }
    }
}
