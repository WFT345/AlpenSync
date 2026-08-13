// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Assembly shape informed by pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/sync/.../SyncBootstrap.kt (createBidirectionalEngines) — one unlock
// shared by the decrypt and encrypt paths; here with our M2 engine + the M3b
// detector/write engine instead of their two engine classes.

package app.alpensync.contacts.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import app.alpensync.contacts.store.AccountContentWiper
import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CardEncryptOp
import app.alpensync.contacts.vcard.ContactDecrypter
import app.alpensync.contacts.vcard.ContactSerializer
import app.alpensync.contacts.vcard.OpenPgpCardCrypto
import app.alpensync.contacts.vcard.OpenPgpCardEncryptor
import app.alpensync.contacts.vcard.VCardMerger
import app.alpensync.contacts.writer.ContactsProviderGateway
import app.alpensync.contacts.writer.DirtyContactReader
import app.alpensync.contacts.writer.DirtyFlagClearer
import app.alpensync.contacts.writer.RawContactDataReader
import app.alpensync.core.api.ContactsMetadataPager
import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import app.alpensync.core.api.ProtonApiFactory
import app.alpensync.core.auth.store.DisconnectNoticeStore
import app.alpensync.core.api.dto.BulkDeleteRequest
import app.alpensync.core.api.dto.BulkDeleteResponse
import app.alpensync.core.api.dto.CreateContactsRequest
import app.alpensync.core.api.dto.CreateContactsResponse
import app.alpensync.core.api.dto.UpdateContactRequest
import app.alpensync.core.api.dto.UpdateContactResponse
import app.alpensync.core.api.http.EndpointFamily
import app.alpensync.core.api.http.HumanVerificationTokenSource
import app.alpensync.core.api.http.mapServerCodes
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.auth.store.EncryptedSecretStore
import app.alpensync.core.auth.store.SecretStore
import app.alpensync.core.db.DatabaseFactory
import app.alpensync.core.keys.KeyringUnlocker
import app.alpensync.core.keys.UnlockedKeySet
import kotlinx.coroutines.flow.toList

/**
 * Production wiring for the M3b two-way pipeline (detect → push → pull, ADR
 * 0007 Section 1): SecretStore → session → Retrofit stack → keyring unlock →
 * card crypto (decrypt AND encrypt off the one unlock) → Room + the
 * Keystore-wrapped canonical store → provider gateway. Used by both the
 * SyncAdapter and the debug screen's "Sync now" button, so both exercise the
 * identical code path.
 *
 * No-session contract (task hard rule): with no logged-in session or no
 * persisted key material this returns null with exactly one SafeLog line —
 * never a crash, never a network call.
 */
object ContactsSyncBootstrap {

    /**
     * Builds the runner, or null when there is nothing to sync with. Throws
     * IOException / HumanVerificationRequiredException /
     * AppVersionRejectedException / KeyringUnlockException from the user /
     * addresses fetch + keyring unlock — the caller maps them to SyncResult.
     */
    suspend fun createRunner(
        context: Context,
        provider: ContentProviderClient,
        account: Account,
    ): ContactsSyncRunner? {
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
        val api = buildApi(appContext, account.name, store, session)

        // unlockAll zeroes keyPassword itself (its documented contract).
        val unlocked = KeyringUnlocker.unlockAll(keyPassword, api.getUser(), api.getAddresses())
        val decrypter = ContactDecrypter(
            OpenPgpCardCrypto.build(unlocked.decryptionKeys, unlocked.verificationKeys),
        )
        val db = DatabaseFactory.create(appContext)
        val stores = ContactsSyncStore(db, CanonicalVCardStore.create(db.canonicalVCardDao(), account.name))
        val writer = ContactsProviderGateway(account, provider)
        val dataReader = RawContactDataReader(provider)
        val writeEngine = buildWriteEngine(account, stores, writer, dataReader, decrypter, api, unlocked)
        val pullEngine = ContactsSyncEngine(
            accountName = account.name,
            listMetadata = { ContactsMetadataPager(api).metadata().toList() },
            fetchContact = { id -> api.getContact(id).contact },
            decrypter = decrypter,
            writer = writer,
            stores = stores,
        )
        return ContactsSyncRunner(buildDetector(account, provider, stores, writer, dataReader), writeEngine, pullEngine)
    }

    private fun buildDetector(
        account: Account,
        provider: ContentProviderClient,
        stores: ContactsSyncStore,
        writer: ContactsProviderGateway,
        dataReader: RawContactDataReader,
    ): LocalChangeDetector = LocalChangeDetector(
        accountName = account.name,
        stores = stores,
        writer = writer,
        readDirty = { DirtyContactReader(provider).readDirty(account) },
        readLocal = dataReader::read,
        clearDirty = { rawContactId -> DirtyFlagClearer(provider).clearDirty(account, rawContactId) },
    )

    private fun buildWriteEngine(
        account: Account,
        stores: ContactsSyncStore,
        writer: ContactsProviderGateway,
        dataReader: RawContactDataReader,
        decrypter: ContactDecrypter,
        api: app.alpensync.core.api.ProtonApi,
        unlocked: UnlockedKeySet,
    ): ContactWriteEngine = ContactWriteEngine(
        accountName = account.name,
        db = stores.db,
        pusher = OutboxEntryPusher(
            accountName = account.name,
            stores = stores,
            factory = ContactWriteFactory(ContactSerializer(writeEncryptor(unlocked))),
            api = RetrofitContactWriteApi(api, decrypter),
            readLocal = dataReader::read,
            writer = writer,
            clock = System::currentTimeMillis,
        ),
    )

    /**
     * Self-encryption (research notes Section 1.2): the user primary ring's
     * encryption publics (the subkey on split rings) as recipients, the user
     * primary private key as signer.
     */
    private fun writeEncryptor(unlocked: UnlockedKeySet): CardEncryptOp = OpenPgpCardEncryptor.build(
        encryptionKeys = unlocked.primary.encryptionPublicKeys.ifEmpty { listOf(unlocked.primary.public) },
        signingKey = unlocked.primary.primary,
    )

    /** The M1 Retrofit stack, re-wired: refresh persists into the store; an invalid grant wipes it. */
    private fun buildApi(
        context: Context,
        accountName: String,
        store: SecretStore,
        session: InMemorySession,
    ): app.alpensync.core.api.ProtonApi =
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
                // Wipe, remember why, and ping the user — they may not have
                // the app open. The next launch still shows Relink even if
                // the notification was blocked.
                onSessionInvalid = {
                    DisconnectNoticeStore(context).markRevoked()
                    store.logout()
                    session.clear()
                    RelinkNotifier.notifyDisconnected(context)
                    kotlinx.coroutines.runBlocking { AccountContentWiper.wipe(context, accountName) }
                },
            ),
            humanVerificationTokens = SecretStoreHumanVerificationSource(store),
        ).api
}

/**
 * The write endpoints behind `mapServerCodes` so HTTP failures surface as
 * ProtonServerCodeException (a status the write engine's classifier can
 * read), plus the merge's `theirs` fetch through the M2 decrypt/merge path.
 */
private class RetrofitContactWriteApi(
    private val api: app.alpensync.core.api.ProtonApi,
    private val decrypter: ContactDecrypter,
) : ContactWriteApi {

    override suspend fun create(request: CreateContactsRequest): CreateContactsResponse =
        mapServerCodes(EndpointFamily.CONTACTS) { api.createContacts(request) }

    override suspend fun update(protonContactId: String, request: UpdateContactRequest): UpdateContactResponse =
        mapServerCodes(EndpointFamily.CONTACTS) { api.updateContact(protonContactId, request) }

    override suspend fun delete(request: BulkDeleteRequest): BulkDeleteResponse =
        mapServerCodes(EndpointFamily.CONTACTS) { api.deleteContacts(request) }

    override suspend fun fetchCanonical(protonContactId: String): CanonicalContact? {
        val dto = mapServerCodes(EndpointFamily.CONTACTS) { api.getContact(protonContactId).contact }
        val result = decrypter.decryptContact(dto.cards)
        // A card failure means we cannot see the server state — never push blind.
        if (result.failures.isNotEmpty()) return null
        return VCardMerger.merge(protonContactId, result.cards)
    }
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
