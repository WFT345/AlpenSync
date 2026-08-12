// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Constants shape adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// app/.../account/AccountConstants.kt

package app.alpensync.contacts.account

import android.accounts.Account
import android.provider.ContactsContract

/**
 * The Android `RawContacts.ACCOUNT_TYPE` this app writes under (ADR 0005
 * Section 5, owner-approved). FROZEN at the first M2 build any device
 * installs: changing it later orphans every synced RawContacts row.
 */
const val ACCOUNT_TYPE: String = "app.alpensync.account"

/**
 * The single debug-harness account name (M2d). It is ALSO the SecretStore
 * account id and the Room account_name key, so the login flow, the sync
 * adapter, and the DB all address the same account. The M4 multi-account UX
 * replaces this with the Proton username per account.
 */
const val DEFAULT_ACCOUNT_NAME: String = "default"

/** The ContactsContract authority. Hard-coded by AOSP; do not parameterize. */
const val CONTACTS_AUTHORITY: String = ContactsContract.AUTHORITY

/** The AccountManager handle the login flow creates and the poker syncs. */
fun defaultSyncAccount(): Account = Account(DEFAULT_ACCOUNT_NAME, ACCOUNT_TYPE)
