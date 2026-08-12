// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.vcard

import app.alpensync.core.api.dto.ContactCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Card dispatch + verification policy + fail-closed behavior, all against
 * real in-test BouncyCastle keys and armored payloads (Rule 1: synthetic
 * fixtures only — nothing touches the network).
 */
class ContactDecrypterTest {

    private val userUnlocked = TestCards.unlock(userRing, USER_PASSPHRASE)
    private val addressUnlocked = TestCards.unlock(addressRing, ADDRESS_PASSPHRASE)

    /** The M2 fan-out set: every private key of every ring, all publics (KeyringUnlocker's output shape). */
    private val decrypter = ContactDecrypter(
        OpenPgpCardCrypto.build(
            decryptionKeys = userUnlocked.allPrivateKeys + addressUnlocked.allPrivateKeys,
            verificationKeys = listOf(userUnlocked.public, addressUnlocked.public),
        ),
    )

    @Test fun clearTextCardPassesThroughVerified() {
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 0, data = VCARD_A)))
        assertEquals(1, result.cards.size)
        assertTrue(result.failures.isEmpty())
        assertEquals(VCARD_A, result.cards[0].plaintext)
        assertTrue(result.cards[0].verified)
        assertEquals(CardType.CLEAR_TEXT, result.cards[0].originalType)
    }

    @Test fun encryptedCardDecryptsAndCountsAsVerified() {
        val armored = TestCards.encryptToSubkey(VCARD_A, userRing)
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 1, data = armored)))
        assertEquals(VCARD_A, result.cards.single().plaintext)
        assertTrue(result.cards.single().verified)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun signedCardWithValidSignatureVerifies() {
        val signature = TestCards.signServerStyle(VCARD_A, userUnlocked)
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 2, data = VCARD_A, signature = signature)))
        assertTrue(result.cards.single().verified)
        assertEquals(VCARD_A, result.cards.single().plaintext)
    }

    @Test fun signedCardWithForeignSignatureIsRetainedUnverified() {
        // Signature over DIFFERENT content — parses fine, fails verification.
        val signature = TestCards.signServerStyle(VCARD_B, userUnlocked)
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 2, data = VCARD_A, signature = signature)))
        assertEquals(VCARD_A, result.cards.single().plaintext)
        assertFalse(result.cards.single().verified)
        assertTrue("retained, not dropped", result.failures.isEmpty())
    }

    @Test fun signedCardWithGarbageSignatureIsRetainedUnverified() {
        // Base64-decodable armor whose payload is not a signature packet —
        // the verifier must fail closed, not crash.
        val garbage = "-----BEGIN PGP SIGNATURE-----\nAAAA\n-----END PGP SIGNATURE-----\n"
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 2, data = VCARD_A, signature = garbage)))
        assertEquals(VCARD_A, result.cards.single().plaintext)
        assertFalse(result.cards.single().verified)
    }

    @Test fun signedCardWithoutSignatureIsRetainedUnverified() {
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 2, data = VCARD_A, signature = null)))
        assertEquals(VCARD_A, result.cards.single().plaintext)
        assertFalse(result.cards.single().verified)
    }

    @Test fun encryptedAndSignedCardRoundTrips() {
        val armored = TestCards.encryptToSubkey(VCARD_A, userRing)
        val signature = TestCards.signClientStyle(VCARD_A, userUnlocked)
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 3, data = armored, signature = signature)))
        assertEquals(VCARD_A, result.cards.single().plaintext)
        assertTrue(result.cards.single().verified)
    }

    @Test fun encryptedAndSignedCardWithBadSignatureDecryptsUnverified() {
        val armored = TestCards.encryptToSubkey(VCARD_A, userRing)
        val signature = TestCards.signClientStyle(VCARD_B, userUnlocked)
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 3, data = armored, signature = signature)))
        assertEquals(VCARD_A, result.cards.single().plaintext)
        assertFalse(result.cards.single().verified)
    }

    @Test fun fanOutDecryptsCardEncryptedToAddressRingSubkey() {
        // ADR-0020 regression guard: contacts can be encrypted to ANY active
        // user or address key. This card is addressed to the SECOND ring's
        // encryption subkey; a user-key-only decrypt path would fail here.
        val armored = TestCards.encryptToSubkey(VCARD_A, addressRing)
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 1, data = armored)))
        assertEquals(VCARD_A, result.cards.single().plaintext)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun wrongKeySetIsATypedFailureNotACrash() {
        val armored = TestCards.encryptToSubkey(VCARD_A, addressRing)
        val userOnly = ContactDecrypter(
            OpenPgpCardCrypto.build(userUnlocked.allPrivateKeys, listOf(userUnlocked.public)),
        )
        val result = userOnly.decryptContact(listOf(ContactCardDto(type = 1, data = armored)))
        assertTrue(result.cards.isEmpty())
        assertEquals(CardFailureReason.DECRYPT_FAILED, result.failures.single().reason)
    }

    @Test fun truncatedArmorIsATypedFailureAndOtherCardsSurvive() {
        val truncated = TestCards.encryptToSubkey(VCARD_A, userRing).dropLast(ARMOR_TRUNCATION)
        val clear = ContactCardDto(type = 0, data = VCARD_B)
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 1, data = truncated), clear))
        assertEquals(listOf(VCARD_B), result.cards.map { it.plaintext })
        assertEquals(CardFailureReason.DECRYPT_FAILED, result.failures.single().reason)
        assertEquals(0, result.failures.single().cardIndex)
    }

    @Test fun unknownWireTypeIsSkippedAndCounted() {
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 7, data = VCARD_A)))
        assertTrue(result.cards.isEmpty())
        assertEquals(CardFailureReason.UNKNOWN_TYPE, result.failures.single().reason)
        assertEquals(7, result.failures.single().wireType)
    }

    @Test fun blankCardIsSkippedAndCounted() {
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 0, data = "  \n ")))
        assertTrue(result.cards.isEmpty())
        assertEquals(CardFailureReason.EMPTY_CARD, result.failures.single().reason)
    }

    @Test fun oversizedCardIsRefusedBeforeCrypto() {
        val huge = VCARD_A.padEnd(ContactDecrypter.MAX_CARD_CHARS + 1, 'x')
        val result = decrypter.decryptContact(listOf(ContactCardDto(type = 1, data = huge)))
        assertTrue(result.cards.isEmpty())
        assertEquals(CardFailureReason.CARD_TOO_LARGE, result.failures.single().reason)
    }

    @Test fun cardsBeyondThePerContactCapAreCountedNotProcessed() {
        val cards = List(ContactDecrypter.MAX_CARDS_PER_CONTACT + 2) { ContactCardDto(type = 0, data = VCARD_A) }
        val result = decrypter.decryptContact(cards)
        assertEquals(ContactDecrypter.MAX_CARDS_PER_CONTACT, result.cards.size)
        assertEquals(2, result.failures.count { it.reason == CardFailureReason.TOO_MANY_CARDS })
    }

    companion object {
        private val USER_PASSPHRASE = "user-passphrase".toCharArray()
        private val ADDRESS_PASSPHRASE = "address-passphrase".toCharArray()
        private const val ARMOR_TRUNCATION = 40

        private val VCARD_A = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Alice Example\r\nEND:VCARD\r\n"
        private val VCARD_B = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob Example\r\nEND:VCARD\r\n"

        private lateinit var userRing: org.bouncycastle.openpgp.PGPSecretKeyRing
        private lateinit var addressRing: org.bouncycastle.openpgp.PGPSecretKeyRing

        @BeforeClass @JvmStatic fun generateKeys() {
            userRing = TestCards.generateRing(USER_PASSPHRASE, "alpensync-test-user")
            addressRing = TestCards.generateRing(ADDRESS_PASSPHRASE, "alpensync-test-address")
        }
    }
}
