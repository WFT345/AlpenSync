// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Shape adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts
// @ bf9b0c5, path core/contacts-writer/src/androidTest/.../ContactsContractInstrumentedTest.kt
// — trimmed to the M2 write-set (no chip rows, no groups) and our writer API.
// Synthetic fixtures only (Rule 1): every value below is invented test data.

package app.alpensync.contacts.writer

import android.Manifest
import android.accounts.Account
import android.content.ContentProviderClient
import android.graphics.Bitmap
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.alpensync.contacts.vcard.ProjectedAddress
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedIm
import app.alpensync.contacts.vcard.ProjectedOrganization
import app.alpensync.contacts.vcard.ProjectedPhone
import app.alpensync.contacts.vcard.ProjectedPhoto
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-provider round-trip tests for the M2d writer on an emulator: what
 * Robolectric cannot prove — actual ContentValues landing in
 * ContactsContract, photo BLOB round-trip, delete-and-reinsert keeping the
 * RawContacts._ID stable, and syncadapter-decorated deletes leaving nothing
 * behind. Each test gets a unique account; tearDown wipes it.
 */
@RunWith(AndroidJUnit4::class)
class ContactsWriterInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    private lateinit var provider: ContentProviderClient
    private lateinit var account: Account

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        provider = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
            ?: throw AssertionError("no ContactsProvider client")
        account = Account("test-${UUID.randomUUID()}", "app.alpensync.account.test")
    }

    @After
    fun tearDown() {
        val uri = SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        provider.delete(
            uri,
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name),
        )
        provider.close()
    }

    @Test
    fun create_then_read_back_every_field_kind_including_photo_bytes() {
        val photoBytes = syntheticJpeg()
        val contact = fullContact(photoBytes)
        BatchApplier(provider).apply(account, listOf(RawContactOpIntent.CreateContact(contact)))

        val rawId = RawContactReader(provider).readExisting(account)["c1"]
        assertNotNull("RawContact must exist after create", rawId)
        rawId!!

        assertEquals(listOf("Alice Example"), column(rawId, StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME))
        assertEquals(listOf("alice@example.org"), column(rawId, Email.CONTENT_ITEM_TYPE, Email.ADDRESS))
        assertEquals(listOf("+1 555 0100"), column(rawId, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER))
        assertEquals(
            listOf(Phone.TYPE_MOBILE.toString()),
            column(rawId, Phone.CONTENT_ITEM_TYPE, Phone.TYPE),
        )
        assertEquals(listOf("Springfield"), column(rawId, StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.CITY))
        assertEquals(listOf("Initech"), column(rawId, Organization.CONTENT_ITEM_TYPE, Organization.COMPANY))
        assertEquals(listOf("Engineer"), column(rawId, Organization.CONTENT_ITEM_TYPE, Organization.TITLE))
        assertEquals(listOf("a note"), column(rawId, Note.CONTENT_ITEM_TYPE, Note.NOTE))
        assertEquals(listOf("alice@xmpp.example"), column(rawId, Im.CONTENT_ITEM_TYPE, Im.DATA))
        assertEquals(listOf("https://example.org"), column(rawId, Website.CONTENT_ITEM_TYPE, Website.URL))

        val photo = photoBytes(rawId)
        assertNotNull("photo row must exist", photo)
        // The provider RE-ENCODES the stored photo (observed on the API 36
        // emulator: a few header bytes differ), so a bit-exact read-back
        // assert is wrong here. What must hold: the bytes survive as a valid
        // image of the same size. Change detection never reads these bytes —
        // content_hash covers the pre-write blob (research notes §1.6).
        val decoded = android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo!!.size)
        assertNotNull("stored photo must decode as an image", decoded)
        assertEquals(64, decoded?.width)
        assertEquals(64, decoded?.height)
    }

    @Test
    fun update_replaces_child_rows_under_the_same_raw_contact_id() {
        BatchApplier(provider).apply(account, listOf(RawContactOpIntent.CreateContact(fullContact(null))))
        val rawId = RawContactReader(provider).readExisting(account).getValue("c1")

        val updated = fullContact(null).copy(
            displayName = "Alice Renamed",
            emails = listOf(ProjectedEmail("new@example.org", emptyList(), isPrimary = true)),
            phones = emptyList(),
        )
        BatchApplier(provider).apply(account, listOf(RawContactOpIntent.UpdateContact(rawId, updated)))

        val after = RawContactReader(provider).readExisting(account)
        assertEquals("raw-contact ID must survive delete-and-reinsert", rawId, after["c1"])
        assertEquals(listOf("Alice Renamed"), column(rawId, StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME))
        assertEquals(listOf("new@example.org"), column(rawId, Email.CONTENT_ITEM_TYPE, Email.ADDRESS))
        assertTrue("stale phone row must be gone", column(rawId, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER).isEmpty())
    }

    @Test
    fun delete_removes_the_raw_contact_without_a_resurrecting_tombstone() {
        BatchApplier(provider).apply(account, listOf(RawContactOpIntent.CreateContact(fullContact(null))))
        assertEquals(1, RawContactReader(provider).readExisting(account).size)

        BatchApplier(provider).apply(account, listOf(RawContactOpIntent.DeleteContact("c1")))

        assertTrue(RawContactReader(provider).readExisting(account).isEmpty())
        val deleted = provider.query(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type),
            arrayOf(RawContacts._ID),
            "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.DELETED} = 1",
            arrayOf(account.name),
            null,
        )?.use { it.count } ?: 0
        assertEquals("no provider tombstone may linger after a syncadapter delete", 0, deleted)
    }

    private fun column(rawId: Long, mimeType: String, column: String): List<String?> {
        val out = mutableListOf<String?>()
        provider.query(
            Data.CONTENT_URI,
            arrayOf(column),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawId.toString(), mimeType),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) out += if (cursor.isNull(0)) null else cursor.getString(0)
        }
        return out
    }

    private fun photoBytes(rawId: Long): ByteArray? =
        provider.query(
            Data.CONTENT_URI,
            arrayOf(Photo.PHOTO),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }

    private fun fullContact(photo: ByteArray?): ProjectedContact = ProjectedContact(
        protonContactId = "c1",
        protonUid = "uid-1",
        displayName = "Alice Example",
        structuredName = null,
        emails = listOf(ProjectedEmail("alice@example.org", listOf("home"), isPrimary = true)),
        phones = listOf(ProjectedPhone("+1 555 0100", listOf("mobile"), isPrimary = true)),
        addresses = listOf(
            ProjectedAddress(null, null, "1 Main St", "Springfield", null, "00000", "US", listOf("home"), true),
        ),
        organization = ProjectedOrganization("Initech", "R&D", "Engineer"),
        notes = listOf("a note"),
        imAccounts = listOf(ProjectedIm("alice@xmpp.example", "xmpp", listOf("home"))),
        photo = photo?.let { ProjectedPhoto(it, "image/jpeg") },
        urls = listOf("https://example.org"),
        birthday = null,
        anniversary = null,
    )

    private fun syntheticJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
