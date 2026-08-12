// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Shape informed by pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts
// @ bf9b0c5, path core/contacts-writer/src/test/.../ContactsContractOpsTest.kt —
// structural assertions (op kinds, counts, URI decoration); ContentProviderOperation
// values aren't introspectable via the public API, so field-value verification is
// the instrumented test's job.

package app.alpensync.contacts.writer

import android.accounts.Account
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import app.alpensync.contacts.vcard.ProjectedAddress
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedIm
import app.alpensync.contacts.vcard.ProjectedOrganization
import app.alpensync.contacts.vcard.ProjectedPhone
import app.alpensync.contacts.vcard.ProjectedPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ContactsContractOpsTest {

    private val account = Account("default", "app.alpensync.account")

    @Test
    fun create_emits_raw_insert_plus_one_row_per_field_kind() {
        val ops = ContactsContractOps.build(account, RawContactOpIntent.CreateContact(baseContact()))
        // 1 RawContacts + name + email + phone + postal + org + note + im + photo + website.
        assertEquals(10, ops.size)
        assertTrue("all ops in a Create batch must be inserts", ops.all { it.isInsert })
        ops.forEach { op ->
            assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
            assertEquals("default", op.uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
            assertEquals("app.alpensync.account", op.uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
        }
    }

    @Test
    fun create_with_no_name_omits_the_StructuredName_row() {
        // Aggregator-friendliness (research notes §3.3): no FN and no N pieces
        // means NO name row, so a peer RawContact's real name survives.
        val contact = baseContact().copy(
            displayName = null,
            emails = emptyList(),
            phones = listOf(ProjectedPhone("+39 333 0000000", listOf("cell"), isPrimary = false)),
            addresses = emptyList(),
            organization = null,
            notes = emptyList(),
            imAccounts = emptyList(),
            photo = null,
            urls = emptyList(),
        )
        val ops = ContactsContractOps.build(account, RawContactOpIntent.CreateContact(contact))
        assertEquals(2, ops.size) // RawContacts + Phone only
        assertTrue(ops.all { it.isInsert })
    }

    @Test
    fun update_deletes_child_rows_then_reinserts_against_the_stable_id() {
        val ops = ContactsContractOps.build(account, RawContactOpIntent.UpdateContact(42L, baseContact()))
        // 1 delete + 9 child re-inserts (no RawContacts op on update).
        assertEquals(10, ops.size)
        assertTrue("first op must wipe the child Data rows", ops[0].isDelete)
        assertTrue("everything after the wipe must be inserts", ops.drop(1).all { it.isInsert })
        ops.forEach { op ->
            assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        }
    }

    @Test
    fun delete_targets_the_raw_contacts_uri_with_syncadapter_decoration() {
        val ops = ContactsContractOps.build(account, RawContactOpIntent.DeleteContact("c1"))
        assertEquals(1, ops.size)
        assertTrue(ops[0].isDelete)
        assertEquals("true", ops[0].uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(RawContacts.CONTENT_URI, ops[0].uri.buildUpon().clearQuery().build())
    }

    @Test
    fun baseIdx_shifts_back_references_without_changing_op_count() {
        // baseIdx is the absolute position of the RawContacts insert in the
        // final batch; the chunker's re-anchoring relies on build() honoring it.
        val atZero = ContactsContractOps.build(account, RawContactOpIntent.CreateContact(baseContact()), baseIdx = 0)
        val atSeven = ContactsContractOps.build(account, RawContactOpIntent.CreateContact(baseContact()), baseIdx = 7)
        assertEquals(atZero.size, atSeven.size)
    }

    private fun baseContact(): ProjectedContact = ProjectedContact(
        protonContactId = "c1",
        protonUid = "uid-1",
        displayName = "Alice Example",
        structuredName = null,
        emails = listOf(ProjectedEmail("alice@example.org", listOf("home"), isPrimary = true)),
        phones = listOf(ProjectedPhone("+1 555 0100", listOf("mobile"), isPrimary = true)),
        addresses = listOf(
            ProjectedAddress(
                poBox = null,
                extendedAddress = null,
                street = "1 Main St",
                locality = "Springfield",
                region = null,
                postalCode = "00000",
                country = "US",
                types = listOf("home"),
                isPrimary = true,
            ),
        ),
        organization = ProjectedOrganization("Initech", "R&D", "Engineer"),
        notes = listOf("a note"),
        imAccounts = listOf(ProjectedIm("alice@xmpp.example", "xmpp", listOf("home"))),
        photo = ProjectedPhoto(ByteArray(100) { it.toByte() }, "image/jpeg"),
        urls = listOf("https://example.org"),
        birthday = null,
        anniversary = null,
    )
}
