package app.alpensync

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.alpensync.contacts.account.ACCOUNT_TYPE
import app.alpensync.contacts.account.CONTACTS_AUTHORITY
import app.alpensync.contacts.account.ContactsAccountSettings
import app.alpensync.contacts.account.defaultSyncAccount
import app.alpensync.contacts.sync.ContactsSyncBootstrap
import app.alpensync.contacts.sync.SyncReport
import app.alpensync.contacts.sync.SyncScheduler
import app.alpensync.core.api.http.AppVersionRejectedException
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.keys.KeyringUnlockException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fixed, non-secret failure classes for the debug "Sync now" button. */
enum class SyncErrorKind {
    NO_SESSION,
    HUMAN_VERIFICATION,
    APP_VERSION_REJECTED,
    KEY_UNLOCK,
    NETWORK,

    /** Last-resort containment (containUnexpected) — Rules 5/19, never crash. */
    UNEXPECTED,
}

/**
 * M2d debug wiring for the logged-in screen (debug-harness quality — the
 * real UX is M4): creates the AccountManager sync account after login,
 * schedules the periodic poker, tracks the contacts-permission grant, and
 * runs an immediate sync on demand through the SAME bootstrap the
 * SyncAdapter uses, surfacing the real SyncReport counts.
 *
 * Secrets discipline: this class holds no credentials and logs nothing; the
 * error surface is the fixed [SyncErrorKind] tags only (Rule 1).
 */
class SyncDebugController(private val context: Context) {

    var contactsPermissionGranted by mutableStateOf(false); private set
    var syncAccountReady by mutableStateOf(false); private set
    var periodMinutes by mutableStateOf(SyncScheduler.storedPeriodMinutes(context)); private set
    var syncing by mutableStateOf(false); private set
    var adapterSyncing by mutableStateOf(false); private set
    var lastReport: SyncReport? by mutableStateOf(null); private set
    var lastError: SyncErrorKind? by mutableStateOf(null); private set

    val inFlight: Boolean get() = syncing || adapterSyncing

    private val mainHandler = Handler(Looper.getMainLooper())
    private var syncWatchHandle: Any? = null

    fun refreshPermissionState() {
        contactsPermissionGranted = CONTACTS_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * After a successful login (and on the logged-in screen's first
     * composition): create the AccountManager account, mark it syncable, make
     * its ungrouped contacts visible, and schedule the periodic poker. All
     * steps are idempotent.
     */
    fun ensureAccountAndSchedule() {
        if (!contactsPermissionGranted) return
        val account = defaultSyncAccount()
        val accountManager = AccountManager.get(context)
        syncAccountReady = accountManager.getAccountsByType(ACCOUNT_TYPE).isNotEmpty() ||
            accountManager.addAccountExplicitly(account, null, null)
        if (!syncAccountReady) return
        ContentResolver.setIsSyncable(account, CONTACTS_AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, CONTACTS_AUTHORITY, true)
        ContactsAccountSettings.ensureVisibleAndSyncable(context.contentResolver, account)
        SyncScheduler.schedulePeriodic(context)
        refreshAdapterSyncing()
    }

    /** Watch the system SyncAdapter as well as the in-app Sync now path. */
    fun startWatchingSync() {
        if (syncWatchHandle != null) return
        refreshAdapterSyncing()
        syncWatchHandle = ContentResolver.addStatusChangeListener(
            ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE,
        ) {
            refreshAdapterSyncing()
        }
    }

    fun stopWatchingSync() {
        syncWatchHandle?.let { ContentResolver.removeStatusChangeListener(it) }
        syncWatchHandle = null
    }

    private fun refreshAdapterSyncing() {
        val accounts = AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE)
        val active = accounts.any { ContentResolver.isSyncActive(it, CONTACTS_AUTHORITY) }
        mainHandler.post { adapterSyncing = active }
    }

    fun selectPeriod(minutes: Long) {
        SyncScheduler.setPeriodMinutes(context, minutes)
        periodMinutes = minutes
    }

    /** Immediate sync through the production bootstrap; the report reaches the UI verbatim. */
    suspend fun syncNow() {
        if (syncing) return
        syncing = true
        lastError = null
        try {
            containUnexpected({ lastError = SyncErrorKind.UNEXPECTED }) {
                // The fixed SyncErrorKind tag is the record — exception bodies
                // (which can carry server content) never reach the UI or a log.
                try {
                    val report = withContext(Dispatchers.IO) { runSyncOnce() }
                    if (report == null) lastError = SyncErrorKind.NO_SESSION else lastReport = report
                } catch (ignored: HumanVerificationRequiredException) {
                    lastError = SyncErrorKind.HUMAN_VERIFICATION
                } catch (ignored: AppVersionRejectedException) {
                    lastError = SyncErrorKind.APP_VERSION_REJECTED
                } catch (ignored: KeyringUnlockException) {
                    lastError = SyncErrorKind.KEY_UNLOCK
                } catch (ignored: IOException) {
                    lastError = SyncErrorKind.NETWORK
                }
            }
        } finally {
            syncing = false
        }
    }

    private suspend fun runSyncOnce(): SyncReport? {
        val provider = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
            ?: throw IOException("ContactsProvider unavailable")
        return provider.use {
            ContactsSyncBootstrap.createRunner(context, it, defaultSyncAccount())?.run()
        }
    }

    companion object {
        val CONTACTS_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
    }
}
