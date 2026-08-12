// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.vcard

import ezvcard.Ezvcard
import ezvcard.VCardVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fragment merge + UID policy + the lossless round-trip guard. The
 * round-trip test is the pcontacts regression guard (research notes Section
 * 3.5): properties the Android projection ignores must survive the canonical
 * model intact — pcontacts dropped them at parse time.
 */
class VCardMergerTest {

    @Test fun propertiesFromAllCardsAccumulate() {
        val canonical = merge(
            DecryptedCard(CardType.SIGNED, SIGNED_FRAGMENT, verified = true),
            DecryptedCard(CardType.ENCRYPTED_AND_SIGNED, ENCRYPTED_FRAGMENT, verified = true),
        )
        assertEquals("Alice Example", canonical.vcard.formattedName?.value)
        assertEquals("alice@example.com", canonical.vcard.emails.single().value)
        assertEquals("+41 44 555 12 34", canonical.vcard.telephoneNumbers.single().text)
        assertEquals(2, canonical.cardCount)
        assertTrue(canonical.verified)
        assertEquals(0, canonical.malformedFragmentCount)
    }

    @Test fun uidFromSignedCardIsAccepted() {
        val canonical = merge(DecryptedCard(CardType.SIGNED, SIGNED_FRAGMENT, verified = true))
        assertEquals("urn:uuid:alice-1", canonical.protonUid)
    }

    @Test fun uidFromNonSignedCardIsDiscarded() {
        // Anti-identity-rebinding (research notes Section 1.7): a tampered
        // ENCRYPTED card must not be able to set the contact's UID.
        val tampered = SIGNED_FRAGMENT.replace("urn:uuid:alice-1", "urn:uuid:evil-9")
        val canonical = merge(
            DecryptedCard(CardType.ENCRYPTED, tampered, verified = true),
            DecryptedCard(CardType.SIGNED, SIGNED_FRAGMENT, verified = true),
        )
        assertEquals("urn:uuid:alice-1", canonical.protonUid)
        assertEquals(1, canonical.vcard.properties.filter { it is ezvcard.property.Uid }.size)
    }

    @Test fun malformedFragmentIsCountedAndOthersStillMerge() {
        val canonical = merge(
            DecryptedCard(CardType.CLEAR_TEXT, ":::this is not a vcard:::", verified = true),
            DecryptedCard(CardType.SIGNED, SIGNED_FRAGMENT, verified = true),
        )
        assertEquals(1, canonical.malformedFragmentCount)
        assertEquals("Alice Example", canonical.vcard.formattedName?.value)
    }

    @Test fun unverifiedSignatureBearingCardMarksContactUnverified() {
        val canonical = merge(
            DecryptedCard(CardType.SIGNED, SIGNED_FRAGMENT, verified = false),
            DecryptedCard(CardType.ENCRYPTED, ENCRYPTED_FRAGMENT, verified = true),
        )
        assertFalse(canonical.verified)
        assertEquals(1, canonical.unverifiedCardCount)
    }

    @Test fun losslessRoundTripPreservesUnmappedProperties() {
        // BDAY, URL, NICKNAME, CATEGORIES and an unknown X-PROP are all
        // outside the Android projection; the canonical model must keep them.
        val canonical = merge(DecryptedCard(CardType.ENCRYPTED_AND_SIGNED, RICH_FRAGMENT, verified = true))

        val reserialized = Ezvcard.write(canonical.vcard).version(VCardVersion.V4_0).prodId(false).go()
        val reparsed = Ezvcard.parse(reserialized).first()

        assertEquals("custom-value-123", reparsed.getExtendedProperty("X-CUSTOM-PROP")?.value)
        assertEquals("1985-04-12", reparsed.birthday?.date?.toString())
        assertEquals("https://example.com/alice", reparsed.urls.single().value)
        assertEquals(listOf("Ally"), reparsed.nickname?.values)
        assertEquals(listOf("Friends", "Work"), reparsed.categories?.values)
    }

    @Test fun emptyInputYieldsEmptyCanonicalContact() {
        val canonical = VCardMerger.merge("contact-0", emptyList())
        assertTrue(canonical.vcard.properties.isEmpty())
        assertNull(canonical.protonUid)
        assertTrue(canonical.verified)
        assertEquals(0, canonical.cardCount)
    }

    private fun merge(vararg cards: DecryptedCard) = VCardMerger.merge("contact-1", cards.toList())

    private companion object {
        private const val SIGNED_FRAGMENT =
            "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "FN:Alice Example\r\n" +
                "UID:urn:uuid:alice-1\r\n" +
                "EMAIL;PREF=1:alice@example.com\r\n" +
                "END:VCARD\r\n"

        private const val ENCRYPTED_FRAGMENT =
            "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "TEL;TYPE=cell:+41 44 555 12 34\r\n" +
                "END:VCARD\r\n"

        private const val RICH_FRAGMENT =
            "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "FN:Alice Example\r\n" +
                "BDAY:19850412\r\n" +
                "URL:https://example.com/alice\r\n" +
                "NICKNAME:Ally\r\n" +
                "CATEGORIES:Friends,Work\r\n" +
                "X-CUSTOM-PROP:custom-value-123\r\n" +
                "END:VCARD\r\n"
    }
}
