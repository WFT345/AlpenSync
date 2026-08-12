// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.vcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical → projection mapping. Covers the name cases (incl. the accepted
 * name-only gap), verbatim phones, primary-first ordering, photo policy, and
 * the M2b field set (URLs, BDAY/ANNIVERSARY included).
 */
class ContactProjectionTest {

    @Test fun fnBecomesDisplayName() {
        val projected = project("FN:Alice Example\r\nEMAIL:alice@example.com\r\n")
        assertEquals("Alice Example", projected.displayName)
        assertNull(projected.structuredName)
    }

    @Test fun displayNameIsSynthesizedFromNWhenFnMissing() {
        val projected = project("N:Example;Alice;Marie;Dr.;Jr.\r\nEMAIL:alice@example.com\r\n")
        assertEquals("Dr. Alice Marie Example Jr.", projected.displayName)
        val name = projected.structuredName
        assertEquals("Alice", name?.given)
        assertEquals("Example", name?.family)
        assertEquals(listOf("Marie"), name?.additionalNames)
        assertEquals(listOf("Dr."), name?.prefixes)
        assertEquals(listOf("Jr."), name?.suffixes)
    }

    @Test fun noFnAndNoNYieldsNoNameAtAll() {
        // The aggregator-friendliness case (research notes Section 3.3): the
        // writer must get nulls so it writes NO StructuredName row.
        val projected = project("EMAIL:alice@example.com\r\n")
        assertNull(projected.displayName)
        assertNull(projected.structuredName)
    }

    @Test fun nameOnlyContactProjectsButFailsTheSyncableGuard() {
        // ADR 0005 open question 3, accepted gap: name-only contacts are not
        // written to the provider at M2 (the Proton copy is untouched).
        val projected = project("FN:Bob\r\n")
        assertEquals("Bob", projected.displayName)
        assertFalse(projected.hasSyncableFields())
        assertTrue(project("FN:Bob\r\nTEL:+41 44 555 12 34\r\n").hasSyncableFields())
    }

    @Test fun emailsAreOrderedPrimaryFirstWithTypes() {
        val projected = project(
            "EMAIL;TYPE=home:alice@home.example\r\n" +
                "EMAIL;TYPE=work;PREF=1:alice@work.example\r\n",
        )
        assertEquals(listOf("alice@work.example", "alice@home.example"), projected.emails.map { it.address })
        assertTrue(projected.emails[0].isPrimary)
        assertFalse(projected.emails[1].isPrimary)
        assertEquals(listOf("work"), projected.emails[0].types)
    }

    @Test fun phoneNumbersAreVerbatimWithTypeTokens() {
        val projected = project(
            "TEL;TYPE=cell:+41 44 555 12 34\r\n" +
                "TEL;TYPE=fax,home:+41 44 555 99 00\r\n",
        )
        assertEquals("+41 44 555 12 34", projected.phones[0].number)
        assertEquals(listOf("cell"), projected.phones[0].types)
        assertEquals("+41 44 555 99 00", projected.phones[1].number)
        assertEquals(listOf("fax", "home"), projected.phones[1].types)
    }

    @Test fun addressesMapComponentsAndBlankOnesAreDropped() {
        val projected = project(
            "ADR;TYPE=home:PO 7;Floor 2;Bahnhofstrasse 1;Zurich;ZH;8001;Switzerland\r\n" +
                "ADR:;;;;;;\r\n",
        )
        val address = projected.addresses.single()
        assertEquals("PO 7", address.poBox)
        assertEquals("Floor 2", address.extendedAddress)
        assertEquals("Bahnhofstrasse 1", address.street)
        assertEquals("Zurich", address.locality)
        assertEquals("ZH", address.region)
        assertEquals("8001", address.postalCode)
        assertEquals("Switzerland", address.country)
        assertEquals(listOf("home"), address.types)
    }

    @Test fun organizationAndTitleProjectIntoOneRow() {
        val projected = project("ORG:Example Corp;Engineering\r\nTITLE:Developer\r\n")
        assertEquals("Example Corp", projected.organization?.company)
        assertEquals("Engineering", projected.organization?.department)
        assertEquals("Developer", projected.organization?.title)
    }

    @Test fun notesImppsAndUrlsProject() {
        val projected = project(
            "NOTE:Met at the conference\r\n" +
                "IMPP:xmpp:alice@xmpp.example\r\n" +
                "URL:https://example.com/alice\r\n",
        )
        assertEquals(listOf("Met at the conference"), projected.notes)
        assertEquals("alice@xmpp.example", projected.imAccounts.single().handle)
        assertEquals("xmpp", projected.imAccounts.single().protocol)
        assertEquals(listOf("https://example.com/alice"), projected.urls)
    }

    @Test fun firstInlinePhotoWinsUrlPhotosAreSkipped() {
        val projected = project(
            "PHOTO;VALUE=URI:https://example.com/photo.jpg\r\n" +
                "PHOTO:data:image/gif;base64,R0lGODlhAQABAAAAACw=\r\n",
        )
        val photo = projected.photo
        assertTrue(photo != null && photo.data.isNotEmpty())
        assertEquals("image/gif", photo?.mimeType)
    }

    @Test fun birthdayAndAnniversaryProjectAsValueStrings() {
        val projected = project("BDAY:19850412\r\nANNIVERSARY:20070615\r\n")
        assertEquals("1985-04-12", projected.birthday)
        assertEquals("2007-06-15", projected.anniversary)
    }

    @Test fun garbageOnlyFragmentYieldsEmptyProjection() {
        val projected = project(":::not valid:::\r\n")
        assertNull(projected.displayName)
        assertTrue(projected.emails.isEmpty())
        assertFalse(projected.hasSyncableFields())
    }

    private fun project(fragmentBody: String): ProjectedContact {
        val fragment = "BEGIN:VCARD\r\nVERSION:4.0\r\n${fragmentBody}END:VCARD\r\n"
        val canonical = VCardMerger.merge(
            "contact-1",
            listOf(DecryptedCard(CardType.SIGNED, fragment, verified = true)),
        )
        return ContactProjection.project(canonical)
    }
}
