// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../DirtyFlagClearer.kt. Deviation: provider
// failure surfaces as IOException (same single failure type as the rest of
// this package).

package app.alpensync.contacts.writer

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.os.RemoteException
import android.provider.ContactsContract.RawContacts
import java.io.IOException

/**
 * Clears the DIRTY flag on a RawContact after the outbox has captured the
 * pending change (ADR 0007 Section 2) — or after the hash gate proved the
 * flag a lie (metadata-only change). The URI is decorated with
 * `caller_is_syncadapter=true` so the clear itself doesn't re-set the flag
 * (ADR 0005 Section 6).
 */
class DirtyFlagClearer(private val provider: ContentProviderClient) {

    @Throws(IOException::class)
    fun clearDirty(account: Account, rawContactId: Long) {
        val uri = SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        val values = ContentValues(1).apply { put(RawContacts.DIRTY, 0) }
        try {
            provider.update(uri, values, "${RawContacts._ID} = ?", arrayOf(rawContactId.toString()))
        } catch (e: RemoteException) {
            throw IOException("ContactsProvider update transport failure", e)
        }
    }
}
