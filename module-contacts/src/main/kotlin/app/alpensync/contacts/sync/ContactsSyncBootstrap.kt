// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Assembly shape informed by pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/sync/.../SyncBootstrap.kt — here scoped to the M2 one-way engine.

package app.alpensync.contacts.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import app.alpensync.contacts.vcard.ContactDecrypter
import app.alpensync.contacts.vcard.OpenPgpCardCrypto
import app.alpensync.contacts.writer.ContactsProviderGateway
import app.alpensync.core.api.ContactsMetadataPager
import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import app.alpensync.core.api.ProtonApiFactory
import app.alpensync.core.api.http.HumanVerificationTokenSource
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.auth.store.EncryptedSecretStore
import app.alpensync.core.auth.store.SecretStore
import app.alpensync.core.db.DatabaseFactory
import app.alpensync.core.keys.KeyringUnlocker
import kotlinx.coroutines.flow.toList

/**
 * Production wiring for [ContactsSyncEngine] (M2d): SecretStore → session →
 * Retrofit stack → keyring unlock → card decrypter → Room → writer gateway.
 * Used by both the SyncAdapter and the debug screen's "Sync now" button, so
 * both exercise the identical code path.
 *
 * No-session contract (task hard rule): with no logged-in session or no
 * persisted key material this returns null with exactly one SafeLog line —
 * never a crash, never a network call.
 */
object ContactsSyncBootstrap {

    /**
     * Builds the engine, or null when there is nothing to sync with. Throws
     * IOException / HumanVerificationRequiredException /
     * AppVersionRejectedException / KeyringUnlockException from the user /
     * addresses fetch + keyring unlock — the caller maps them to SyncResult.
     */
    suspend fun createEngine(
        context: Context,
        provider: ContentProviderClient,
        account: Account,
    ): ContactsSyncEngine? {
        val appContext = context.applicationContext
        val store = EncryptedSecretStore.create(appContext, account.name)
        val uid = store.uid()
        val accessToken = store.accessToken()
        val keyPassword = store.keyPassword()
        if (uid.isNullOrBlank() || accessToken.isNullOrBlank() || keyPassword == null) {
            SafeLog.log(SafeLog.Event.SYNC_SKIPPED_NO_SESSION)
            return null
        }

        val session = InMemorySession(uid, accessToken)
        val api = buildApi(store, session)

        // unlockAll zeroes keyPassword itself (its documented contract).
        val unlocked = KeyringUnlocker.unlockAll(keyPassword, api.getUser(), api.getAddresses())
        val decrypter = ContactDecrypter(
            OpenPgpCardCrypto.build(unlocked.decryptionKeys, unlocked.verificationKeys),
        )
        return ContactsSyncEngine(
            accountName = account.name,
            listMetadata = { ContactsMetadataPager(api).metadata().toList() },
            fetchContact = { id -> api.getContact(id).contact },
            decrypter = decrypter,
            writer = ContactsProviderGateway(account, provider),
            db = DatabaseFactory.create(appContext),
        )
    }

    /** The M1 Retrofit stack, re-wired: refresh persists into the store; an invalid grant wipes it. */
    private fun buildApi(store: SecretStore, session: InMemorySession): app.alpensync.core.api.ProtonApi =
        ProtonApiFactory(
            config = ProtonApiConfig(),
            session = session,
            refreshConfig = ProtonApiFactory.RefreshConfig(
                mutableSession = session,
                getRefreshToken = store::refreshToken,
                onTokensRefreshed = { newAccess, newRefresh ->
                    store.setAccessToken(newAccess)
                    store.setRefreshToken(newRefresh)
                },
                // No UI here: wipe the store; the next run takes the
                // no-session path and the app shows logged-out on next open.
                onSessionInvalid = {
                    store.logout()
                    session.clear()
                },
            ),
            humanVerificationTokens = SecretStoreHumanVerificationSource(store),
        ).api
}

/** Bridges the HTTP layer's human-verification headers to the SecretStore (ADR 0004 Q4). */
private class SecretStoreHumanVerificationSource(
    private val store: SecretStore,
) : HumanVerificationTokenSource {
    override fun token(): String? = store.humanVerificationToken()
    override fun tokenType(): String? = store.humanVerificationTokenType()

    override fun clear() {
        store.setHumanVerificationToken(null)
        store.setHumanVerificationTokenType(null)
    }
}
