// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.store

import android.content.Context
import androidx.room.withTransaction
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.DatabaseFactory

/**
 * Logout / invalid-session wipe of contact content at rest: canonical
 * vCards, conflict-copy payloads, the outbox, and the per-account KEK.
 */
object AccountContentWiper {

    suspend fun wipe(db: AlpenSyncDatabase, store: CanonicalVCardStore, accountName: String) {
        db.withTransaction {
            db.conflictCopyDao().deleteAllForAccount(accountName)
            db.outboxDao().deleteAllForAccount(accountName)
            store.wipeAccount(accountName)
        }
    }

    suspend fun wipe(context: Context, accountName: String) {
        val db = DatabaseFactory.create(context)
        try {
            wipe(db, CanonicalVCardStore.create(db.canonicalVCardDao(), accountName), accountName)
        } finally {
            db.close()
        }
    }
}
