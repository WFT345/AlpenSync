// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../SyncAdapterUri.kt

package app.alpensync.contacts.writer

import android.net.Uri
import android.provider.ContactsContract

/**
 * Every ContactsContract write originating from a SyncAdapter MUST carry
 * `?caller_is_syncadapter=true` (ADR 0005 Section 6). Without it, deletes
 * leave a provider tombstone the next sync resurrects as a duplicate.
 *
 * This is the ONLY helper in the codebase allowed to build a ContactsContract
 * write URI — deletes and the (M4) logout wipe all go through it.
 */
object SyncAdapterUri {

    fun decorate(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
            .build()
}
