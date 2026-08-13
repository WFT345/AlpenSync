// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.vcard

import ezvcard.Ezvcard
import ezvcard.VCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADR 0006 Option-B merge matrix for keyed multi-value families
 * (emails/phones/addresses/urls/…): per-entry add/edit/delete on each side,
 * both-add collisions, delete-vs-edit both directions, and the theirs-first
 * ordering contract. Scalars and photos live in [ContactMergerTest].
 */
class ContactMergerKeyedTest {

    @Test fun local_addition_merges_into_the_server_list() {
        val ours = BASE.replace("END:VCARD", "EMAIL:alice@new.example\r\nEND:VCARD")
        val outcome = merge(BASE, ours, BASE)
        assertEquals(setOf("alice@home.example", "alice@new.example"), outcome.emailAddresses())
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun server_addition_merges_into_the_local_list() {
        val theirs = BASE.replace("END:VCARD", "EMAIL:alice@new.example\r\nEND:VCARD")
        val outcome = merge(BASE, BASE, theirs)
        assertEquals(setOf("alice@home.example", "alice@new.example"), outcome.emailAddresses())
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun both_sides_add_different_entries_both_survive() {
        val ours = BASE.replace("END:VCARD", "EMAIL:alice@local.example\r\nEND:VCARD")
        val theirs = BASE.replace("END:VCARD", "EMAIL:alice@server.example\r\nEND:VCARD")
        val outcome = merge(BASE, ours, theirs)
        assertEquals(
            setOf("alice@home.example", "alice@local.example", "alice@server.example"),
            outcome.emailAddresses(),
        )
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun both_sides_add_identical_entry_once_no_conflict() {
        val add = BASE.replace("END:VCARD", "EMAIL;TYPE=work:alice@new.example\r\nEND:VCARD")
        val outcome = merge(BASE, add, add)
        assertEquals(setOf("alice@home.example", "alice@new.example"), outcome.emailAddresses())
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun both_sides_add_same_key_with_different_params_is_a_server_wins_conflict() {
        val ours = BASE.replace("END:VCARD", "EMAIL;TYPE=home:alice@new.example\r\nEND:VCARD")
        val theirs = BASE.replace("END:VCARD", "EMAIL;TYPE=work;PREF=1:alice@new.example\r\nEND:VCARD")
        val outcome = merge(BASE, ours, theirs)

        val added = outcome.projection.emails.single { it.address == "alice@new.example" }
        assertEquals(listOf("work"), added.types)
        assertTrue(added.isPrimary)
        val conflict = outcome.conflicts.single()
        assertEquals(ContactMerger.Fields.EMAILS, conflict.field)
        assertTrue(conflict.localHash != null && conflict.serverHash != null)
    }

    @Test fun local_delete_with_server_unchanged_drops_the_entry() {
        val ours = BASE.replace("EMAIL;TYPE=home:alice@home.example\r\n", "")
        val outcome = merge(BASE, ours, BASE)
        assertTrue(outcome.projection.emails.isEmpty())
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun server_delete_with_local_unchanged_drops_the_entry() {
        val theirs = BASE.replace("EMAIL;TYPE=home:alice@home.example\r\n", "")
        val outcome = merge(BASE, BASE, theirs)
        assertTrue(outcome.projection.emails.isEmpty())
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun local_delete_vs_server_edit_conflicts_and_the_server_edit_survives() {
        val ours = BASE.replace("EMAIL;TYPE=home:alice@home.example\r\n", "")
        val theirs = BASE.replace("EMAIL;TYPE=home", "EMAIL;TYPE=work")
        val outcome = merge(BASE, ours, theirs)

        val kept = outcome.projection.emails.single()
        assertEquals("alice@home.example", kept.address)
        assertEquals(listOf("work"), kept.types)
        val conflict = outcome.conflicts.single()
        assertEquals(ContactMerger.Fields.EMAILS, conflict.field)
        assertNull(conflict.localHash)
    }

    @Test fun local_edit_vs_server_delete_conflicts_and_the_entry_is_dropped() {
        val ours = BASE.replace("EMAIL;TYPE=home", "EMAIL;TYPE=work")
        val theirs = BASE.replace("EMAIL;TYPE=home:alice@home.example\r\n", "")
        val outcome = merge(BASE, ours, theirs)

        assertTrue(outcome.projection.emails.isEmpty())
        val conflict = outcome.conflicts.single()
        assertEquals(ContactMerger.Fields.EMAILS, conflict.field)
        assertTrue(conflict.localHash != null)
        assertNull(conflict.serverHash)
    }

    @Test fun both_sides_edit_the_same_entry_differently_server_wins() {
        // Same key (the number), different params: a genuine both-sides edit.
        // A number change itself is delete+add under value-keying — the
        // delete-side tests above cover that path.
        val ours = BASE.replace("TEL;TYPE=cell:+1-111", "TEL;TYPE=home:+1-111")
        val theirs = BASE.replace("TEL;TYPE=cell:+1-111", "TEL;TYPE=work;PREF=1:+1-111")
        val outcome = merge(BASE, ours, theirs)

        val kept = outcome.projection.phones.single()
        assertEquals("+1-111", kept.number)
        assertEquals(listOf("work"), kept.types)
        assertTrue(kept.isPrimary)
        assertEquals(ContactMerger.Fields.PHONES, outcome.conflicts.single().field)
    }

    @Test fun disjoint_keyed_and_scalar_edits_merge_without_conflicts() {
        val ours = BASE.replace("END:VCARD", "TEL;TYPE=home:+9-999\r\nEND:VCARD")
        val theirs = BASE.replace("Alice Base", "Alice Server")
        val outcome = merge(BASE, ours, theirs)

        assertEquals("Alice Server", outcome.projection.displayName)
        assertEquals(setOf("+1-111", "+9-999"), outcome.projection.phones.map { it.number }.toSet())
        assertTrue(outcome.conflicts.isEmpty())
        assertTrue(outcome.autoMerged)
    }

    @Test fun distinct_addresses_that_concatenate_identically_do_not_collide() {
        val ours = BASE.replace(
            "END:VCARD",
            "ADR:;;1;23;;;;\r\nEND:VCARD",
        )
        val theirs = BASE.replace(
            "END:VCARD",
            "ADR:;;12;3;;;;\r\nEND:VCARD",
        )
        val outcome = merge(BASE, ours, theirs)
        val streets = outcome.projection.addresses.map { it.street }.toSet()
        assertEquals(setOf("1", "12"), streets)
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun both_sides_add_different_urls_both_survive() {
        val ours = BASE.replace("END:VCARD", "URL:https://local.example\r\nEND:VCARD")
        val theirs = BASE.replace("END:VCARD", "URL:https://server.example\r\nEND:VCARD")
        val outcome = merge(BASE, ours, theirs)
        assertEquals(
            setOf("https://base.example", "https://local.example", "https://server.example"),
            outcome.projection.urls.toSet(),
        )
        assertTrue(outcome.conflicts.isEmpty())
    }

    @Test fun untouched_family_reproduces_the_server_list_exactly_in_order() {
        val theirs = BASE.replace(
            "EMAIL;TYPE=home:alice@home.example",
            "EMAIL;TYPE=work:z@example\r\nEMAIL;TYPE=home:a@example",
        )
        val outcome = merge(BASE, BASE, theirs)
        // The locally-untouched family iterates theirs-first: the server's
        // order and entries reproduce verbatim.
        assertEquals(listOf("z@example", "a@example"), outcome.projection.emails.map { it.address })
        assertTrue(outcome.conflicts.isEmpty())
    }

    private fun ContactMerger.MergeOutcome.emailAddresses(): Set<String> =
        projection.emails.map { it.address }.toSet()

    private fun merge(base: String, ours: String, theirs: String): ContactMerger.MergeOutcome =
        ContactMerger.merge(ID, vcard(base), vcard(ours), vcard(theirs))

    private fun vcard(text: String): VCard = Ezvcard.parse(text).first()

    private companion object {
        const val ID = "pc-1"

        const val BASE = "BEGIN:VCARD\r\n" +
            "VERSION:4.0\r\n" +
            "FN:Alice Base\r\n" +
            "UID:urn:uuid:a1\r\n" +
            "EMAIL;TYPE=home:alice@home.example\r\n" +
            "TEL;TYPE=cell:+1-111\r\n" +
            "URL:https://base.example\r\n" +
            "END:VCARD\r\n"
    }
}
