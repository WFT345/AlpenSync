// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Query shape adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/contacts-writer/.../RawContactDataReader.kt. Deviations: the row model
// is the vcard layer's ProjectedContact directly (no intermediate ContactRow
// mirror — same call we made for the write direction); type ints map back to
// vCard tokens through the TypeMappers inverses; provider failure surfaces as
// IOException (the single failure type of this package).

package app.alpensync.contacts.writer

import android.content.ContentProviderClient
import android.database.Cursor
import android.os.RemoteException
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import app.alpensync.contacts.vcard.ProjectedAddress
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedIm
import app.alpensync.contacts.vcard.ProjectedName
import app.alpensync.contacts.vcard.ProjectedOrganization
import app.alpensync.contacts.vcard.ProjectedPhone
import app.alpensync.contacts.vcard.ProjectedPhoto
import java.io.IOException
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName

/**
 * The M3b dirty-detection read-back: all Data rows of one RawContact → the
 * [ProjectedContact] the provider currently represents. This is the read-path
 * inverse of [ContactDataOps], so a contact WE wrote reads back as the same
 * projection (modulo the provider's lossy spots — multi-value name pieces and
 * photo mime types don't round-trip; [ProjectionReconciler] repairs exactly
 * those from the stored baseline before any hash comparison).
 *
 * Null return = the RawContact has no Data rows at all (it vanished or was
 * never populated); the caller decides what that means per op. The
 * cursor-parsing step ([parse]) is split out for MatrixCursor-based tests,
 * mirroring [RawContactReader].
 */
class RawContactDataReader(private val provider: ContentProviderClient) {

    @Throws(IOException::class)
    fun read(rawContactId: Long, protonContactId: String): ProjectedContact? {
        val cursor: Cursor? = try {
            provider.query(
                Data.CONTENT_URI,
                PROJECTION,
                "${Data.RAW_CONTACT_ID} = ?",
                arrayOf(rawContactId.toString()),
                "${Data._ID} ASC",
            )
        } catch (e: RemoteException) {
            throw IOException("ContactsProvider query transport failure", e)
        }
        return cursor?.use { parse(it, protonContactId) }
    }

    companion object {
        private val PROJECTION = arrayOf(
            Data.MIMETYPE,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5,
            Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10,
            Data.DATA15, Data.IS_PRIMARY,
        )

        /** Pure parser — split out so tests can drive it with a MatrixCursor. */
        fun parse(cursor: Cursor, protonContactId: String): ProjectedContact? {
            if (cursor.count == 0) return null
            val row = RowColumns(cursor)
            val acc = MutableProjection()
            while (cursor.moveToNext()) {
                accumulate(acc, row.mime(), row)
            }
            return acc.build(protonContactId)
        }

        private fun accumulate(acc: MutableProjection, mime: String?, row: RowColumns) {
            when (mime) {
                CCStructuredName.CONTENT_ITEM_TYPE -> acc.readName(row)
                Email.CONTENT_ITEM_TYPE -> acc.readEmail(row)
                Phone.CONTENT_ITEM_TYPE -> acc.readPhone(row)
                StructuredPostal.CONTENT_ITEM_TYPE -> acc.readPostal(row)
                else -> accumulateOther(acc, mime, row)
            }
        }

        private fun accumulateOther(acc: MutableProjection, mime: String?, row: RowColumns) {
            when (mime) {
                CCOrganization.CONTENT_ITEM_TYPE -> acc.readOrganization(row)
                Note.CONTENT_ITEM_TYPE -> acc.readNote(row)
                Im.CONTENT_ITEM_TYPE -> acc.readIm(row)
                Website.CONTENT_ITEM_TYPE -> acc.readWebsite(row)
                Photo.CONTENT_ITEM_TYPE -> acc.readPhoto(row)
            }
        }
    }
}

/** Cursor column accessors resolved once per parse. */
internal class RowColumns(private val cursor: Cursor) {
    private val columns = HashMap<String, Int>()

    fun mime(): String? = get(Data.MIMETYPE)
    fun primary(): Boolean = cursor.getInt(getOrResolve(Data.IS_PRIMARY)) == 1
    fun get(column: String): String? = cursor.getString(getOrResolve(column))?.takeIf { it.isNotBlank() }
    fun blob(column: String): ByteArray? = cursor.getBlob(getOrResolve(column))?.takeIf { it.isNotEmpty() }

    private fun getOrResolve(column: String): Int =
        columns.getOrPut(column) { cursor.getColumnIndexOrThrow(column) }
}

/** The accumulating half of the parse — one var/list per projected field. */
private class MutableProjection {
    var displayName: String? = null
    var structuredName: ProjectedName? = null
    val emails = mutableListOf<ProjectedEmail>()
    val phones = mutableListOf<ProjectedPhone>()
    val addresses = mutableListOf<ProjectedAddress>()
    var organization: ProjectedOrganization? = null
    val notes = mutableListOf<String>()
    val imAccounts = mutableListOf<ProjectedIm>()
    var photo: ProjectedPhoto? = null
    val urls = mutableListOf<String>()

    fun readName(row: RowColumns) {
        displayName = row.get(Data.DATA1)
        val name = ProjectedName(
            given = row.get(Data.DATA2),
            family = row.get(Data.DATA3),
            additionalNames = listOfNotNull(row.get(Data.DATA5)),
            prefixes = listOfNotNull(row.get(Data.DATA4)),
            suffixes = listOfNotNull(row.get(Data.DATA6)),
        )
        structuredName = name.takeIf {
            it.given != null || it.family != null || it.additionalNames.isNotEmpty() ||
                it.prefixes.isNotEmpty() || it.suffixes.isNotEmpty()
        }
    }

    fun readEmail(row: RowColumns) {
        val address = row.get(Data.DATA1) ?: return
        emails += ProjectedEmail(address, EmailTypeMapper.fromAndroidType(row.type()), row.primary())
    }

    fun readPhone(row: RowColumns) {
        val number = row.get(Data.DATA1) ?: return
        phones += ProjectedPhone(number, PhoneTypeMapper.fromAndroidType(row.type()), row.primary())
    }

    fun readPostal(row: RowColumns) {
        val address = ProjectedAddress(
            poBox = row.get(Data.DATA5),
            extendedAddress = row.get(Data.DATA6),
            street = row.get(Data.DATA4),
            locality = row.get(Data.DATA7),
            region = row.get(Data.DATA8),
            postalCode = row.get(Data.DATA9),
            country = row.get(Data.DATA10),
            types = PostalAddressTypeMapper.fromAndroidType(row.type()),
            isPrimary = row.primary(),
        )
        // Same rule as the projection: blank-only addresses carry no information.
        if (listOfNotNull(
                address.poBox, address.extendedAddress, address.street,
                address.locality, address.region, address.postalCode, address.country,
            ).isNotEmpty()
        ) {
            addresses += address
        }
    }

    fun readOrganization(row: RowColumns) {
        organization = ProjectedOrganization(
            company = row.get(Data.DATA1),
            department = row.get(Data.DATA5),
            title = row.get(Data.DATA4),
        )
    }

    fun readNote(row: RowColumns) {
        row.get(Data.DATA1)?.let { notes += it }
    }

    fun readIm(row: RowColumns) {
        val handle = row.get(Data.DATA1) ?: return
        imAccounts += ProjectedIm(
            handle = handle,
            protocol = ImProtocolMapper.protocolFromAndroid(row.getInt(Data.DATA5), row.get(Data.DATA6)),
            types = ImProtocolMapper.typeFromAndroid(row.type()),
        )
    }

    fun readWebsite(row: RowColumns) {
        row.get(Data.DATA1)?.let { urls += it }
    }

    fun readPhoto(row: RowColumns) {
        row.blob(Data.DATA15)?.let { photo = ProjectedPhoto(it, mimeType = null) }
    }

    fun build(protonContactId: String): ProjectedContact = ProjectedContact(
        protonContactId = protonContactId,
        protonUid = null,
        displayName = displayName,
        structuredName = structuredName,
        emails = emails,
        phones = phones,
        addresses = addresses,
        organization = organization?.takeUnless { it.company == null && it.department == null && it.title == null },
        notes = notes,
        imAccounts = imAccounts,
        photo = photo,
        urls = urls,
        // Events rows are outside the M2 write set, so they are never read
        // back; the reconciler carries the baseline's values forward.
        birthday = null,
        anniversary = null,
    )
}

private fun RowColumns.type(): Int = getInt(Data.DATA2)

private fun RowColumns.getInt(column: String): Int = get(column)?.toIntOrNull() ?: 0
