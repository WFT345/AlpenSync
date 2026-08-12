// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CardType
import app.alpensync.contacts.vcard.ContactProjection
import app.alpensync.contacts.vcard.DecryptedCard
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.VCardMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hash determinism and the content/photo split: content hash covers the
 * mapped projection EXCLUDING the photo; photo hash is bit-exact over the
 * inline photo. No normalization happens — case, ordering of values, and
 * verbatim phone numbers all matter (only TYPE-token order is canonicalized,
 * since a vCard TYPE list is a set).
 */
class ContactHasherTest {

    @Test fun hashIsDeterministicForTheSameProjection() {
        val contact = project("FN:Alice\r\nEMAIL:alice@example.com\r\nTEL:+41 44 555 12 34\r\n")
        assertEquals(ContactHasher.contentHash(contact), ContactHasher.contentHash(contact))
    }

    @Test fun contentChangeChangesContentHash() {
        val before = project("EMAIL:alice@example.com\r\n")
        val after = project("EMAIL:alice@example.org\r\n")
        assertNotEquals(ContactHasher.contentHash(before), ContactHasher.contentHash(after))
    }

    @Test fun fuzzyNameDifferenceIsJustAContentChange() {
        // Research notes Section 3.3: "Bob" vs "Bob Smith" must not trigger
        // any special matching — it hashes differently, that is all.
        val bob = project("FN:Bob\r\nTEL:123\r\n")
        val bobSmith = project("FN:Bob Smith\r\nTEL:123\r\n")
        assertNotEquals(ContactHasher.contentHash(bob), ContactHasher.contentHash(bobSmith))
    }

    @Test fun phoneNumbersAreHashedVerbatim() {
        val plain = project("TEL:0445551234\r\n")
        val formatted = project("TEL:+41 44 555 12 34\r\n")
        assertNotEquals(ContactHasher.contentHash(plain), ContactHasher.contentHash(formatted))
    }

    @Test fun typeTokenOrderDoesNotAffectTheHash() {
        val one = project("EMAIL;TYPE=home,work:alice@example.com\r\n")
        val two = project("EMAIL;TYPE=work,home:alice@example.com\r\n")
        assertEquals(ContactHasher.contentHash(one), ContactHasher.contentHash(two))
    }

    @Test fun photoBytesAreExcludedFromTheContentHash() {
        val without = project("EMAIL:alice@example.com\r\n")
        val with = project(
            "EMAIL:alice@example.com\r\n" +
                "PHOTO:data:image/gif;base64,R0lGODlhAQABAAAAACw=\r\n",
        )
        assertEquals(ContactHasher.contentHash(without), ContactHasher.contentHash(with))
        assertNull(ContactHasher.photoHash(without))
    }

    @Test fun photoHashChangesWithPhotoBytes() {
        val gif = project("PHOTO:data:image/gif;base64,R0lGODlhAQABAAAAACw=\r\nTEL:1\r\n")
        val other = project("PHOTO:data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==\r\nTEL:1\r\n")
        val gifHash = ContactHasher.photoHash(gif)
        val otherHash = ContactHasher.photoHash(other)
        assertNotEquals(gifHash, otherHash)
    }

    private fun project(fragmentBody: String): ProjectedContact {
        val fragment = "BEGIN:VCARD\r\nVERSION:4.0\r\n${fragmentBody}END:VCARD\r\n"
        val canonical: CanonicalContact = VCardMerger.merge(
            "contact-1",
            listOf(DecryptedCard(CardType.SIGNED, fragment, verified = true)),
        )
        return ContactProjection.project(canonical)
    }
}
