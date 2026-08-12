package app.alpensync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.alpensync.contacts.account.DEFAULT_ACCOUNT_NAME
import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApiConfig
import app.alpensync.core.api.ProtonApiFactory
import app.alpensync.core.api.dto.GetAddressesResponse
import app.alpensync.core.api.http.HumanVerificationTokenSource
import app.alpensync.core.api.http.EndpointFamily
import app.alpensync.core.api.http.ProtonServerCodeException
import app.alpensync.core.api.http.mapServerCodes
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.auth.LoginResult
import app.alpensync.core.auth.SrpLoginOrchestrator
import app.alpensync.core.auth.srp.SrpClient
import app.alpensync.core.auth.store.EncryptedSecretStore
import app.alpensync.core.auth.store.SecretStore
import app.alpensync.core.keys.KeyringUnlockException
import app.alpensync.core.keys.KeyringUnlocker
import app.alpensync.core.keys.TokenDecryptException
import app.alpensync.core.keys.TokenDecryptor
import app.alpensync.core.keys.UnlockedKey
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * State holder + core wiring for the M1 debug login screen (plan Section 6
 * acceptance). Deliberately NOT a ViewModel/repository/DI stack (plan
 * Rule 14): the activity creates one instance and Compose drives its
 * suspend methods; network work hops to [Dispatchers.IO] and results land
 * back on the caller's (main) context.
 *
 * What it wires — all real core code, nothing re-implemented here:
 *  - [EncryptedSecretStore] — Keystore-wrapped tokens + keyPassword (ADR 0004 §3)
 *  - [InMemorySession] — hydrated from the store on relaunch (persistence proof)
 *  - [ProtonApiFactory] — interceptors + the 401→refresh authenticator
 *  - [SrpLoginOrchestrator] — SRP-6a login incl. pinned modulus verification
 *  - [KeyringUnlocker]/[TokenDecryptor] — the "one decrypted blob" M1 proof
 *
 * Secrets discipline (Rule 1): the password arrives as a CharArray and is
 * zeroed by the orchestrator; this class has no logger and never logs field
 * contents; decrypted Token BYTES are zeroed immediately — only their
 * length reaches the UI.
 *
 * Containment (plan Rules 5/19, live test 1 lesson): every public entry
 * point runs through [containUnexpected] — a failure that escaped all typed
 * paths becomes an honest UNEXPECTED error state, never a crashed coroutine.
 */
class LoginController(context: Context) {

    var state: LoginUiState by mutableStateOf(LoginUiState.LoggedOut())
        private set

    // EncryptedSharedPreferences init touches the Keystore; doing it once at
    // activity creation keeps that predictable (ADR 0004 §3 factory contract).
    private val store: SecretStore = EncryptedSecretStore.create(
        context.applicationContext,
        ACCOUNT_ID,
    )
    private val session = InMemorySession()

    // Bridges :core:api's HV-header needs to the SecretStore (ADR 0004 Q4):
    // the token lives in the encrypted prefs; clear() mirrors the store.
    private val humanVerificationTokens = object : HumanVerificationTokenSource {
        override fun token(): String? = store.humanVerificationToken()
        override fun tokenType(): String? = store.humanVerificationTokenType()
        override fun clear() {
            store.setHumanVerificationToken(null)
            store.setHumanVerificationTokenType(null)
        }
    }

    private val api = ProtonApiFactory(
        config = ProtonApiConfig(),
        session = session,
        refreshConfig = ProtonApiFactory.RefreshConfig(
            mutableSession = session,
            getRefreshToken = store::refreshToken,
            onTokensRefreshed = { accessToken, refreshToken ->
                store.setAccessToken(accessToken)
                store.setRefreshToken(refreshToken)
            },
            onSessionInvalid = ::onSessionInvalid,
        ),
        humanVerificationTokens = humanVerificationTokens,
    ).api

    private val orchestrator = SrpLoginOrchestrator(
        api = api,
        srp = SrpClient(),
        secretStore = store,
        session = session,
    )

    /** Username from the last fresh login; null for a restored session. */
    private var loggedInUsername: String? = null

    /** True when the current LoggedIn came from the persisted session. */
    private var restoredSession: Boolean = false

    /**
     * Credentials kept only across an in-app HV challenge so the solved
     * challenge can retry the login the user already submitted without
     * re-asking for the password (mirrors the orchestrator's
     * pendingTwoFactorPassword discipline). [PendingHvCredentials] owns the
     * copy-in/copy-out rule that keeps the stash and the retry from sharing
     * one array — see its KDoc for what sharing cost.
     */
    private val pendingHv = PendingHvCredentials()

    init {
        // Relaunch proof (M1 acceptance): a persisted session restores
        // straight into LoggedIn without re-asking for credentials.
        val uid = store.uid()
        val accessToken = store.accessToken()
        if (!uid.isNullOrBlank() && !accessToken.isNullOrBlank()) {
            session.update(uid = uid, accessToken = accessToken)
            restoredSession = true
            state = LoginUiState.LoggedIn(username = null, restoredSession = true)
        }
    }

    /** Full SRP login. [password] is zeroed by the orchestrator on return. */
    suspend fun login(username: String, password: CharArray) {
        state = LoginUiState.LoggingIn
        pendingHv.clear()
        // Copy before the orchestrator consumes and zeroes [password].
        // stash() takes its OWN copy, so this local is wiped either way:
        // the stash and the retry must never share one array.
        val retryPassword = password.copyOf()
        val result = containUnexpected({ t -> retryPassword.fill('\u0000'); state = unexpectedError(t); null }) {
            withContext(Dispatchers.IO) { orchestrator.login(username, password) }
        } ?: return
        try {
            if ((result as? LoginResult.HumanVerificationRequired)?.challengeToStateOrNull() != null) {
                pendingHv.stash(username, retryPassword)
            }
        } finally {
            retryPassword.fill('\u0000')
        }
        applyLoginResult(result)
    }

    /** Second step of a 2FA login; [code] already passed normalizeTotpCode. */
    suspend fun submitTwoFactorCode(code: String) {
        state = LoginUiState.LoggingIn
        val result = containUnexpected({ t -> state = unexpectedError(t); null }) {
            withContext(Dispatchers.IO) { orchestrator.submitTwoFactorCode(code) }
        } ?: return
        applyLoginResult(result)
    }

    /**
     * The in-app challenge was solved: persist token + type (the API layer
     * attaches them as `x-pm-human-verification-token[-type]` on the retry)
     * and re-run the login the user already submitted. Without a stashed
     * retry (the challenge interrupted the 2FA call, where the code cannot
     * be replayed) return to the code prompt — that session survives.
     */
    suspend fun completeHumanVerification(token: String, tokenType: String) {
        // A SUCCESS dispatch after the sheet already left composition (the
        // page can fire the bridge more than once) must not disturb the
        // follow-up state — only the visible sheet may complete.
        if (state !is LoginUiState.HumanVerification) return
        containUnexpected({ t -> state = unexpectedError(t); null }) {
            store.setHumanVerificationToken(token)
            store.setHumanVerificationTokenType(tokenType)
            // Timeline marker: separates "the user solved the challenge"
            // from "the retry that followed then failed" in logcat. Without
            // it the two are indistinguishable after the fact.
            SafeLog.log(SafeLog.Event.HUMAN_VERIFICATION_COMPLETED)
        } ?: return
        // take() hands back a copy and wipes the stash in ONE step. Reading
        // the field and then clearing it aliased a single array: the wipe
        // emptied the very password the retry was about to hash, so Proton
        // answered 8002 against a correct one (live tests 1-3).
        val (username, password) = pendingHv.take() ?: run {
            state = LoginUiState.NeedsTotp
            return
        }
        try {
            login(username, password)
        } finally {
            password.fill('\u0000')
        }
    }

    /** Challenge dismissed or failed to load → manual-instructions error. */
    fun onHumanVerificationCancelled() {
        pendingHv.clear()
        state = LoginUiState.Error(LoginErrorKind.HUMAN_VERIFICATION)
    }

    /**
     * Fetches /users + /addresses, unlocks the keyring with the persisted
     * keyPassword, and decrypts one address-key Token as the M1 "decryption
     * OK" proof. Runs after a fresh login and after a session restore.
     */
    suspend fun loadAccount() {
        val current = state as? LoginUiState.LoggedIn ?: return
        containUnexpected({ t -> state = unexpectedError(t, fromAccountLoad = true) }) {
            state = try {
                when (val snapshot = withContext(Dispatchers.IO) { fetchSnapshot() }) {
                    null -> {
                        // Tokens without key material (e.g. a login abandoned at
                        // the 2FA step) can never unlock — wipe and start over.
                        wipeSession()
                        LoginUiState.LoggedOut(notice = LogoutNotice.SESSION_INCOMPLETE)
                    }
                    else -> current.copy(snapshot = snapshot)
                }
            } catch (e: ProtonServerCodeException) {
                LoginUiState.Error(
                    LoginErrorKind.SERVER_CODE,
                    detail = serverCodeDetail(e.protonCode, e.endpointFamily),
                    fromAccountLoad = true,
                )
            } catch (e: IOException) {
                accountLoadError(LoginErrorKind.INFO_UNREACHABLE, e)
            } catch (e: KeyringUnlockException) {
                accountLoadError(LoginErrorKind.KEY_SETUP, e)
            } catch (e: IllegalArgumentException) {
                // Strict DTO parsing failing closed (Rule 5) — the API shape moved.
                accountLoadError(LoginErrorKind.UNKNOWN, e)
            }
        }
    }

    /**
     * Best-effort server-side revoke (a network failure must not block the
     * local wipe), then the core wipe path: SecretStore.logout() deletes the
     * encrypted prefs AND the Keystore alias; the in-memory session follows.
     */
    suspend fun logout() {
        containUnexpected({ t -> state = unexpectedError(t) }) {
            withContext(Dispatchers.IO) {
                runCatching { api.revoke() } // deliberately best-effort — the wipe is the guarantee
                wipeSession()
            }
            state = LoginUiState.LoggedOut()
        }
    }

    /** Error-screen "Back" — login failures already left a wiped store. */
    fun backToLogin() {
        pendingHv.clear()
        state = LoginUiState.LoggedOut()
    }

    /** Error-screen "Retry" re-runs the account fetch on the live session. */
    fun retryAccountLoad() {
        state = LoginUiState.LoggedIn(username = loggedInUsername, restoredSession = restoredSession)
    }

    private fun applyLoginResult(result: LoginResult) {
        if (result is LoginResult.Success) {
            loggedInUsername = result.username
            restoredSession = false
        }
        state = mapLoginResultToState(result)
    }

    /** Rules 5/19 last-resort surface: an honest error; the detail is the
     * exception's class name only — never its message (Rule 1). */
    private fun unexpectedError(t: Throwable, fromAccountLoad: Boolean = false) =
        LoginUiState.Error(
            LoginErrorKind.UNEXPECTED,
            detail = t.javaClass.simpleName,
            fromAccountLoad = fromAccountLoad,
        )

    private fun accountLoadError(kind: LoginErrorKind, e: Exception) =
        LoginUiState.Error(kind, detail = e.javaClass.simpleName, fromAccountLoad = true)

    /** Null return = persisted tokens without key material (caller wipes). */
    private suspend fun fetchSnapshot(): AccountSnapshot? {
        val keyPassword = store.keyPassword() ?: return null
        val user = mapServerCodes(EndpointFamily.USERS) { api.getUser() }
        val addresses = mapServerCodes(EndpointFamily.ADDRESSES) { api.getAddresses() }
        // unlockAll zeroes keyPassword itself (its documented contract).
        val unlocked = KeyringUnlocker.unlockAll(keyPassword, user, addresses)
        return AccountSnapshot(
            addresses = addresses.addresses.map { address ->
                AddressLine(
                    email = address.email,
                    send = address.send == 1,
                    receive = address.receive == 1,
                    keyCount = address.keys.size,
                )
            },
            decryptionKeyCount = unlocked.decryptionKeys.size,
            tokenProof = decryptTokenProof(addresses, unlocked.primary),
        )
    }

    /**
     * The M1 acceptance proof: decrypt one address-key Token with the
     * primary user key. Only the plaintext LENGTH is reported — the bytes
     * are zeroed immediately and never reach the UI or a log (Rule 1).
     */
    private fun decryptTokenProof(addresses: GetAddressesResponse, primary: UnlockedKey): TokenProof {
        val token = addresses.addresses
            .flatMap { it.keys }
            .firstOrNull { it.active == 1 && it.token != null }
            ?.token
            ?: return TokenProof.NoTokenKey
        val plaintext = try {
            TokenDecryptor.decrypt(token, primary.allPrivateKeys)
        } catch (e: TokenDecryptException) {
            return TokenProof.Failed(e.javaClass.simpleName)
        }
        return try {
            TokenProof.Decrypted(plaintext.size)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun wipeSession() {
        store.logout()
        session.clear()
        loggedInUsername = null
        restoredSession = false
        pendingHv.clear()
    }

    /** Fired by RefreshingAuthenticator when the server rejects the refresh. */
    private fun onSessionInvalid() {
        wipeSession()
        state = LoginUiState.LoggedOut(notice = LogoutNotice.SESSION_EXPIRED)
    }

    private companion object {
        // Single debug account id (plan Section 5.5 per-account storage, M1
        // single-account UI). Shared with the M2d sync adapter + Room
        // account_name key via :module-contacts' account constants.
        const val ACCOUNT_ID = DEFAULT_ACCOUNT_NAME
    }
}
