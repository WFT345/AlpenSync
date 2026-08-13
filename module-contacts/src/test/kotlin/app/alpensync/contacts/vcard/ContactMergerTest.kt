// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.vcard

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.property.FormattedName
import ezvcard.property.Photo
import ezvcard.property.Uid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADR 0006 Option-B merge matrix, scalar fields + photos + unmapped
 * properties (keyed multi-value families live in [ContactMergerKeyedTest]).
 * Fixtures are real vCard text so the projections the merger derives are the
 * production ones, not hand-built shortcuts.
 */
class ContactMergerTest {

    @Test fun unchanged_inputs_merge_to_base_with_no_conflicts() {
        val outcome = merge(BASE, BASE, BASE)
        assertEquals("Alice Base", outcome.merged.formattedName?.value)
        assertTrue(outcome.conflicts.isEmpty())
        assertTrue(outcome.autoMerged)
    }

    @Test fun local_only_scalar_edit_is_taken() {
        val outcome = merge(BASE, BASE.replace("Alice Base", "Alice Local"), BASE)
        assertEquals("Alice Local", outcome.projection.displayName)
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun server_only_scalar_edit_is_taken() {
        val outcome = merge(BASE, BASE, BASE.replace("Alice Base", "Alice Server"))
        assertEquals("Alice Server", outcome.projection.displayName)
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun both_sides_same_scalar_value_merge_cleanly() {
        val ours = BASE.replace("Alice Base", "Alice Same")
        val theirs = BASE.replace("Alice Base", "Alice Same")
        val outcome = merge(BASE, ours, theirs)
        assertEquals("Alice Same", outcome.projection.displayName)
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun both_sides_different_scalar_server_wins_with_conflict_record() {
        val ours = BASE.replace("Alice Base", "Alice Local")
        val theirs = BASE.replace("Alice Base", "Alice Server")
        val outcome = merge(BASE, ours, theirs)

        assertEquals("Alice Server", outcome.projection.displayName)
        assertEquals("Alice Server", outcome.merged.formattedName?.value)
        val conflict = outcome.conflicts.single()
        assertEquals(ContactMerger.Fields.FN, conflict.field)
        assertTrue(conflict.localHash != null && conflict.serverHash != null)
        assertTrue(conflict.localHash != conflict.serverHash)
        assertTrue(!outcome.autoMerged)
    }

    @Test fun local_delete_vs_server_edit_conflicts_and_the_edit_wins() {
        // ORG+TITLE removed locally, edited on the server (scalar delete-vs-edit).
        val ours = BASE.replace("ORG:BaseCorp;Eng\r\n", "").replace("TITLE:Dev\r\n", "")
        val theirs = BASE.replace("BaseCorp", "ServerCorp")
        val outcome = merge(BASE, ours, theirs)

        assertEquals("ServerCorp", outcome.projection.organization?.company)
        val conflict = outcome.conflicts.single()
        assertEquals(ContactMerger.Fields.ORGANIZATION, conflict.field)
        assertNull(conflict.localHash) // the local side DELETED the field
        assertTrue(conflict.serverHash != null)
    }

    @Test fun local_edit_vs_server_delete_conflicts_and_the_deletion_wins() {
        val ours = BASE.replace("BaseCorp", "LocalCorp")
        val theirs = BASE.replace("ORG:BaseCorp;Eng\r\n", "").replace("TITLE:Dev\r\n", "")
        val outcome = merge(BASE, ours, theirs)

        assertNull(outcome.projection.organization)
        assertTrue(outcome.merged.organization?.values.isNullOrEmpty())
        val conflict = outcome.conflicts.single()
        assertEquals(ContactMerger.Fields.ORGANIZATION, conflict.field)
        assertTrue(conflict.localHash != null)
        assertNull(conflict.serverHash)
    }

    @Test fun one_side_delete_with_other_side_unchanged_deletes_cleanly() {
        val localDelete = merge(BASE, BASE.replace("NOTE:base note\r\n", ""), BASE)
        assertTrue(localDelete.projection.notes.isEmpty())
        assertTrue(localDelete.conflicts.isEmpty())

        val serverDelete = merge(BASE, BASE, BASE.replace("NOTE:base note\r\n", ""))
        assertTrue(serverDelete.projection.notes.isEmpty())
        assertTrue(serverDelete.conflicts.isEmpty())
    }

    @Test fun birthday_conflict_resolves_server_wins() {
        // ez-vcard normalizes BDAY to the ISO form on projection.
        val ours = BASE.replace("19900101", "19910102")
        val theirs = BASE.replace("19900101", "19921225")
        val outcome = merge(BASE, ours, theirs)

        assertEquals("1992-12-25", outcome.projection.birthday)
        assertEquals(ContactMerger.Fields.BIRTHDAY, outcome.conflicts.single().field)
    }

    @Test fun unmapped_property_rides_the_server_side_while_local_edit_merges() {
        val ours = BASE.replace("alice@home.example", "alice@local.example")
        val theirs = BASE.replace("base-custom", "server-custom")
        val outcome = merge(BASE, ours, theirs)

        // The local email edit merges…
        assertEquals(listOf("alice@local.example"), outcome.projection.emails.map { it.address })
        // …and the server's unmapped X-* property survives VERBATIM (the
        // losslessness premise of storing the canonical base).
        assertEquals("server-custom", outcome.merged.getExtendedProperty("X-CUSTOM")?.value)
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun photo_both_sides_changed_conflicts_and_server_bytes_win() {
        val base = photoCard(byteArrayOf(1))
        val ours = photoCard(byteArrayOf(2))
        val theirs = photoCard(byteArrayOf(3))
        val outcome = ContactMerger.merge(ID, base, ours, theirs)

        assertTrue(outcome.merged.photos.single().data.contentEquals(byteArrayOf(3)))
        val conflict = outcome.conflicts.single()
        assertEquals(ContactMerger.Fields.PHOTO, conflict.field)
        assertTrue(conflict.localHash != null && conflict.serverHash != null)
    }

    @Test fun photo_removed_locally_with_server_unchanged_is_removed() {
        val base = photoCard(byteArrayOf(1))
        val ours = photoCard(null)
        val outcome = ContactMerger.merge(ID, base, ours, base)
        assertTrue(outcome.merged.photos.isEmpty())
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun photo_changed_locally_only_replaces_with_local_bytes() {
        val base = photoCard(byteArrayOf(1))
        val ours = photoCard(byteArrayOf(9))
        val outcome = ContactMerger.merge(ID, base, ours, base)
        assertTrue(outcome.merged.photos.single().data.contentEquals(byteArrayOf(9)))
        assertTrue(outcome.conflicts.isEmpty())
    }

    private fun merge(base: String, ours: String, theirs: String): ContactMerger.MergeOutcome =
        ContactMerger.merge(ID, vcard(base), vcard(ours), vcard(theirs))

    private fun vcard(text: String): VCard = Ezvcard.parse(text).first()

    private fun photoCard(bytes: ByteArray?): VCard = VCard().apply {
        formattedName = FormattedName("Photo Owner")
        uid = Uid("urn:uuid:photo")
        if (bytes != null) addProperty(Photo(bytes, null))
    }

    private companion object {
        const val ID = "pc-1"

        const val BASE = "BEGIN:VCARD\r\n" +
            "VERSION:4.0\r\n" +
            "FN:Alice Base\r\n" +
            "N:Base;Alice;;;\r\n" +
            "UID:urn:uuid:a1\r\n" +
            "EMAIL;TYPE=home:alice@home.example\r\n" +
            "TEL;TYPE=cell:+1-111\r\n" +
            "ORG:BaseCorp;Eng\r\n" +
            "TITLE:Dev\r\n" +
            "NOTE:base note\r\n" +
            "URL:https://base.example\r\n" +
            "BDAY:19900101\r\n" +
            "X-CUSTOM:base-custom\r\n" +
            "END:VCARD\r\n"
    }
}
