// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path app/.../account/ProtonAccountAuthenticator.kt. Deviation: a library
// module can't reference the app's login activity class, so addAccount /
// getAuthToken return the app's own launcher intent (M2d debug harness; the
// M4 onboarding flow gets a dedicated target).

package app.alpensync.contacts.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Stub authenticator registering the `app.alpensync.account` account type so
 * it appears in Settings → Accounts and our RawContacts can claim it.
 *
 * `getAuthToken` deliberately NEVER returns the Proton access token (ADR 0004
 * Section 3 / pcontacts ADR-0016): Android would cache it plaintext in
 * accounts_ce.db. It returns a re-auth Intent instead; the SyncAdapter reads
 * tokens from the Keystore-wrapped SecretStore directly and never calls this.
 */
class AlpenSyncAccountAuthenticator(
    private val context: Context,
) : AbstractAccountAuthenticator(context) {

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = Bundle()

    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(AccountManager.KEY_INTENT, launchIntent(response))
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(AccountManager.KEY_INTENT, launchIntent(response))
    }

    /** Label shown by system account UI for the token type; never a token value. */
    override fun getAuthTokenLabel(authTokenType: String): String = "Proton access token (not exportable)"

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    /** The app's own launcher activity (the M2d login screen lives there). */
    private fun launchIntent(response: AccountAuthenticatorResponse): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response) }
}
