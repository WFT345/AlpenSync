// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path app/.../account/ProtonAuthenticatorService.kt

package app.alpensync.contacts.account

import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Hosts [AlpenSyncAccountAuthenticator] for the AccountManager. Declared in
 * this library's manifest with the `android.accounts.AccountAuthenticator`
 * action + the `@xml/authenticator` meta-data, which is how the system
 * discovers and registers our account type. Exported because AccountManager
 * binds across processes; the intent-filter restricts the binding's use.
 */
class AlpenSyncAuthenticatorService : Service() {

    private lateinit var authenticator: AlpenSyncAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = AlpenSyncAccountAuthenticator(this)
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == AccountManager.ACTION_AUTHENTICATOR_INTENT) {
            authenticator.iBinder
        } else {
            null
        }
}
