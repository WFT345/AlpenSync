// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../RawContactReader.kt. Deviation: provider
// failure surfaces as IOException (same single failure type as BatchApplier).

package app.alpensync.contacts.writer

import android.accounts.Account
import android.content.ContentProviderClient
import android.database.Cursor
import android.os.RemoteException
import android.provider.ContactsContract.RawContacts
import java.io.IOException

/**
 * The SOURCE_ID → RawContacts._ID map for all RawContacts under our account.
 * The engine reads it before applying (a Room-wiped or crash-interrupted run
 * re-discovers the rows it already wrote — the ADR 0005 Section 3 recovery
 * path, SOURCE_ID being written redundantly into the provider) and again
 * after applying to learn the _IDs of freshly created RawContacts.
 */
class RawContactReader(private val provider: ContentProviderClient) {

    @Throws(IOException::class)
    fun readExisting(account: Account): Map<String, Long> {
        val cursor: Cursor? = try {
            provider.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID, RawContacts.SOURCE_ID),
                "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
                arrayOf(account.type, account.name),
                null,
            )
        } catch (e: RemoteException) {
            throw IOException("ContactsProvider query transport failure", e)
        }
        return cursor?.use(RawContactReader::parse) ?: emptyMap()
    }

    companion object {
        /** Pure parser — split out so tests can drive it with a MatrixCursor. */
        fun parse(cursor: Cursor): Map<String, Long> {
            if (cursor.count == 0) return emptyMap()
            val idIdx = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val sourceIdx = cursor.getColumnIndexOrThrow(RawContacts.SOURCE_ID)
            val out = HashMap<String, Long>(cursor.count)
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(sourceIdx) ?: continue
                out[sourceId] = cursor.getLong(idIdx)
            }
            return out
        }
    }
}
