// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.vcard

import app.alpensync.core.api.dto.ContactCardDto
import app.alpensync.core.auth.openpgp.OpenPgpSignatures
import app.alpensync.core.auth.openpgp.VerificationStatus
import app.alpensync.core.keys.TokenDecryptException
import app.alpensync.core.keys.TokenDecryptor
import ezvcard.Ezvcard
import ezvcard.VCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * The write ceremony end-to-end with REAL in-test BouncyCastle keys (Rule 1:
 * synthetic fixtures only): serialize → encrypt+sign → the M2 read path
 * (decrypt+verify+merge) → the same contact back. This is the pcontacts
 * round-trip guard for the WRITE direction (ADR 0007 Section 9).
 */
class CardCeremonyTest {

    private val userUnlocked = TestCards.unlock(userRing, PASSPHRASE.toCharArray())
    private val foreignUnlocked = TestCards.unlock(foreignRing, PASSPHRASE.toCharArray())

    private val serializer = ContactSerializer(
        OpenPgpCardEncryptor.build(
            encryptionKeys = listOf(userUnlocked.public),
            signingKey = userUnlocked.primary,
        ),
    )

    @Test fun serializeThenDecryptMergeRoundTripsLosslessly() {
        val canonical = parse(FULL_VCARD)
        val cards = serializer.serialize(canonical)

        assertEquals(CardType.SIGNED.wireValue, cards[0].type)
        assertEquals(CardType.ENCRYPTED_AND_SIGNED.wireValue, cards[1].type)
        assertTrue(cards[0].data.startsWith("BEGIN:VCARD"))
        assertTrue(cards[1].data.startsWith("-----BEGIN PGP MESSAGE-----"))
        assertNotNull(cards[0].signature)
        assertNotNull(cards[1].signature)

        val merged = readBack(cards, userKeys = true)
        assertEquals(17, merged.vcard.properties.size)
        assertEquals("Alice Example", merged.vcard.formattedName?.value)
        assertEquals("urn:uuid:alice-1", merged.protonUid)
        assertEquals(2, merged.vcard.emails.size)
        assertEquals("+1 555 0100", merged.vcard.telephoneNumbers.single().text)
        assertEquals("Main St 1", merged.vcard.addresses.single().streetAddress)
        assertEquals("Example Corp", merged.vcard.organization?.values?.first())
        assertEquals(listOf("Al"), merged.vcard.nickname?.values)
        assertEquals(listOf("Friends", "Work"), merged.vcard.categories?.values)
        assertEquals("https://alice.example", merged.vcard.urls.single().value)
        assertNotNull(merged.vcard.birthday)
        assertEquals("custom-value", merged.vcard.getExtendedProperty("X-CUSTOM-PROP")?.value)
        assertEquals("friends", merged.vcard.getExtendedProperty("X-PM-LABEL")?.value)
        assertTrue(merged.verified)
    }

    @Test fun ownWriteVerifiesUnderTheM2ReadPathCrypto() {
        val cards = serializer.serialize(parse(FULL_VCARD))
        val result = ContactDecrypter(
            OpenPgpCardCrypto.build(
                decryptionKeys = userUnlocked.allPrivateKeys,
                verificationKeys = listOf(userUnlocked.public),
            ),
        ).decryptContact(cards)

        assertTrue(result.failures.isEmpty())
        assertEquals(2, result.cards.size)
        assertTrue(result.cards.all { it.verified })
    }

    @Test fun wrongKeyFailsClosed() {
        val cards = serializer.serialize(parse(FULL_VCARD))

        // The read path must not crash: the encrypted card becomes a typed
        // DECRYPT_FAILED entry, the SIGNED card fails verification.
        val result = ContactDecrypter(
            OpenPgpCardCrypto.build(
                decryptionKeys = foreignUnlocked.allPrivateKeys,
                verificationKeys = listOf(foreignUnlocked.public),
            ),
        ).decryptContact(cards)
        assertEquals(1, result.failures.size)
        assertEquals(CardFailureReason.DECRYPT_FAILED, result.failures.single().reason)
        assertEquals(1, result.cards.size)
        assertFalse(result.cards.single().verified)

        // And the raw decrypt primitive refuses loudly.
        try {
            TokenDecryptor.decrypt(cards[1].data, foreignUnlocked.allPrivateKeys)
            fail("expected TokenDecryptException with the wrong key set")
        } catch (expected: TokenDecryptException) {
            // fail-closed: no key matches → typed error, never garbage.
        }
    }

    @Test fun tamperedEncryptedCardSignatureFailsVerification() {
        val cards = serializer.serialize(parse(FULL_VCARD))
        val tampered = mutableListOf(cards[0])
        val decrypted = TokenDecryptor.decrypt(cards[1].data, userUnlocked.allPrivateKeys)
        val tamperedPlaintext = String(decrypted, Charsets.UTF_8).replace("Alice", "Mallory")

        // Re-encrypt the tampered text to OUR key but keep the ORIGINAL
        // signature — the M2 verify step must catch the mismatch.
        val reencrypted = TestCards.encryptToSubkey(tamperedPlaintext, userRing)
        tampered += ContactCardDto(
            type = CardType.ENCRYPTED_AND_SIGNED.wireValue,
            data = reencrypted,
            signature = cards[1].signature,
        )

        val result = ContactDecrypter(
            OpenPgpCardCrypto.build(
                decryptionKeys = userUnlocked.allPrivateKeys,
                verificationKeys = listOf(userUnlocked.public),
            ),
        ).decryptContact(tampered)
        val encCard = result.cards.single { it.originalType == CardType.ENCRYPTED_AND_SIGNED }
        assertFalse(encCard.verified)
    }

    @Test fun signedCardVerifiesInCanonicalTextModeAndRejectsForeignSigner() {
        val cards = serializer.serialize(parse(FULL_VCARD))
        val signature = cards[0].signature
        assertNotNull(signature)
        checkNotNull(signature)

        val ownVerify = OpenPgpSignatures.verifyDetached(
            plaintext = cards[0].data.toByteArray(Charsets.UTF_8),
            armoredSignature = signature,
            verificationKeys = listOf(userUnlocked.public),
            canonicalText = true,
            stripTrailingSpaces = true,
        )
        assertEquals(VerificationStatus.SIGNED_AND_VALID, ownVerify)

        val foreignVerify = OpenPgpSignatures.verifyDetached(
            plaintext = cards[0].data.toByteArray(Charsets.UTF_8),
            armoredSignature = signature,
            verificationKeys = listOf(foreignUnlocked.public),
            canonicalText = true,
            stripTrailingSpaces = true,
        )
        assertEquals(VerificationStatus.SIGNED_NO_VERIFIER, foreignVerify)
    }

    /** Feeds cards back through the M2 decrypter + merger. */
    private fun readBack(cards: List<ContactCardDto>, userKeys: Boolean): CanonicalContact {
        val unlocked = if (userKeys) userUnlocked else foreignUnlocked
        val result = ContactDecrypter(
            OpenPgpCardCrypto.build(
                decryptionKeys = unlocked.allPrivateKeys,
                verificationKeys = listOf(unlocked.public),
            ),
        ).decryptContact(cards)
        assertTrue(result.failures.isEmpty())
        return VCardMerger.merge("pc-1", result.cards)
    }

    private fun parse(text: String): VCard = Ezvcard.parse(text).first()

    companion object {
        private const val PASSPHRASE = "test-passphrase"
        private val userRing = TestCards.generateRing(PASSPHRASE.toCharArray(), "write-test-user")
        private val foreignRing = TestCards.generateRing(PASSPHRASE.toCharArray(), "write-test-foreign")

        /** One fixture carrying every property class the losslessness guard covers. */
        private const val FULL_VCARD = """BEGIN:VCARD
VERSION:4.0
FN:Alice Example
UID:urn:uuid:alice-1
N:Example;Alice;Marie;Dr.;Jr.
EMAIL;TYPE=work;PREF=1:alice@work.example
EMAIL:alice@home.example
TEL;TYPE=cell:+1 555 0100
ADR;TYPE=home:;;Main St 1;Springfield;IL;12345;US
ORG:Example Corp;Engineering
TITLE:Engineer
NOTE:First line
IMPP:xmpp:alice@im.example
URL:https://alice.example
BDAY:19860421
NICKNAME:Al
CATEGORIES:Friends,Work
X-CUSTOM-PROP:custom-value
X-PM-LABEL:friends
END:VCARD"""

        @JvmStatic
        @BeforeClass
        fun sanity() {
            // Fixture parses and carries the expected property count — a
            // broken fixture must fail here, not inside an assertion maze.
            val parsed = Ezvcard.parse(FULL_VCARD).first()
            assertEquals(17, parsed.properties.size)
        }
    }
}
