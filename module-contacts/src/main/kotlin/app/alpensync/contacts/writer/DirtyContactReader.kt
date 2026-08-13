// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../DirtyContactReader.kt. Deviation: provider
// failure surfaces as IOException (same single failure type as the rest of
// this package).

package app.alpensync.contacts.writer

import android.accounts.Account
import android.content.ContentProviderClient
import android.database.Cursor
import android.os.RemoteException
import android.provider.ContactsContract.RawContacts
import java.io.IOException

/**
 * Reads RawContacts that Android flagged as locally modified (DIRTY=1) or
 * locally deleted (DELETED=1) under our account — the fast path of the M3b
 * hybrid dirty detection (ADR 0007 Section 2): the flag is the cheap gate,
 * the content hash against the stored baseline is the truth that absorbs
 * false positives. On minSdk 26+ the platform contract is that DIRTY means
 * data changed (research notes Section 3.2 — DAVx⁵'s false-positive
 * workaround is Android-7-only), so no separate verifier table is built; the
 * per-account "hash-diff everything" escape hatch needs no schema change.
 *
 * The cursor-parsing step ([parse]) is split out for MatrixCursor-based
 * testing, mirroring [RawContactReader].
 */
class DirtyContactReader(private val provider: ContentProviderClient) {

    @Throws(IOException::class)
    fun readDirty(account: Account): List<DirtyContact> {
        val cursor: Cursor? = try {
            provider.query(
                RawContacts.CONTENT_URI,
                PROJECTION,
                "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?" +
                    " AND (${RawContacts.DIRTY} = 1 OR ${RawContacts.DELETED} = 1)",
                arrayOf(account.type, account.name),
                null,
            )
        } catch (e: RemoteException) {
            throw IOException("ContactsProvider query transport failure", e)
        }
        return cursor?.use(Companion::parse) ?: emptyList()
    }

    companion object {
        private val PROJECTION = arrayOf(
            RawContacts._ID,
            RawContacts.SOURCE_ID,
            RawContacts.DIRTY,
            RawContacts.DELETED,
        )

        /** Pure parser — split out so tests can drive it with a MatrixCursor. */
        fun parse(cursor: Cursor): List<DirtyContact> {
            if (cursor.count == 0) return emptyList()
            val idIdx = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val sourceIdx = cursor.getColumnIndexOrThrow(RawContacts.SOURCE_ID)
            val dirtyIdx = cursor.getColumnIndexOrThrow(RawContacts.DIRTY)
            val deletedIdx = cursor.getColumnIndexOrThrow(RawContacts.DELETED)
            val out = ArrayList<DirtyContact>(cursor.count)
            while (cursor.moveToNext()) {
                out += DirtyContact(
                    rawContactId = cursor.getLong(idIdx),
                    sourceId = cursor.getString(sourceIdx),
                    isDirty = cursor.getInt(dirtyIdx) == 1,
                    isDeleted = cursor.getInt(deletedIdx) == 1,
                )
            }
            return out
        }
    }
}

/** One flagged RawContact. [sourceId] null = created locally, never synced. */
data class DirtyContact(
    val rawContactId: Long,
    val sourceId: String?,
    val isDirty: Boolean,
    val isDeleted: Boolean,
)
