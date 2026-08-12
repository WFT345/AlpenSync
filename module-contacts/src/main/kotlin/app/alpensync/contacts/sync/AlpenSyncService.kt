// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path app/.../sync/ProtonSyncService.kt

package app.alpensync.contacts.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Hosts [AlpenSyncAdapter] for the AOSP sync framework. The library manifest
 * declares this service with the `android.content.SyncAdapter` action and
 * meta-data pointing at `@xml/syncadapter`, associating the adapter with
 * ContactsContract.AUTHORITY for our account type. Exported because the sync
 * framework binds across processes.
 */
class AlpenSyncService : Service() {

    private lateinit var syncAdapter: AlpenSyncAdapter

    override fun onCreate() {
        super.onCreate()
        syncAdapter = AlpenSyncAdapter(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder = syncAdapter.syncAdapterBinder
}
