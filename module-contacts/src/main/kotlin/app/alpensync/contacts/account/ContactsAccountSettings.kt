// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path app/.../contacts/ContactsAccountSettings.kt (SafeLog instead of their
// Logger). Their [V]-verified finding carries over: without this Settings row
// a sync-adapter account's ungrouped contacts are INVISIBLE in the device
// Contacts app — this is what makes M2's "appears in the dialer" acceptance
// reachable at all.

package app.alpensync.contacts.account

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentValues
import android.provider.ContactsContract
import app.alpensync.contacts.writer.SyncAdapterUri
import app.alpensync.core.api.log.SafeLog

/**
 * Initializes the account-level ContactsProvider Settings row so ungrouped
 * contacts are visible and the account participates in sync
 * (`SHOULD_SYNC=1`, `UNGROUPED_VISIBLE=1`). AOSP defaults
 * `ungrouped_visible=0` for sync-adapter-owned accounts; DAVx⁵ writes the
 * identical row for the same reason.
 *
 * Idempotent (the provider upserts on account_name/account_type), so the
 * SyncAdapter calls it before every run. Failure is non-fatal: OEM providers
 * that reject Settings writes must not break the sync — logged, continue.
 */
object ContactsAccountSettings {

    fun ensureVisibleAndSyncable(resolver: ContentResolver, account: Account): Boolean {
        val uri = SyncAdapterUri.decorate(ContactsContract.Settings.CONTENT_URI, account.name, account.type)
        val values = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        }
        return try {
            resolver.insert(uri, values)
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: RuntimeException,
        ) {
            // OEM ContactsProviders throw arbitrary RuntimeExceptions here;
            // by design this must never break the sync (pcontacts' note).
            SafeLog.log(SafeLog.Event.SYNC_ACCOUNT_SETTINGS_FAILED)
            false
        }
    }
}
