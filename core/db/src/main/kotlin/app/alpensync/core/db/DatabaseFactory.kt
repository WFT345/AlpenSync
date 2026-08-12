// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.db

import android.content.Context
import androidx.room.Room

/**
 * Production construction point for [AlpenSyncDatabase] (tests build their
 * own in-memory instance directly). No destructive-migration fallback is
 * enabled on purpose: a schema bump without a migration must crash loudly in
 * development, not silently drop mappings, tombstones, and sync state.
 */
object DatabaseFactory {

    fun create(context: Context): AlpenSyncDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            AlpenSyncDatabase::class.java,
            AlpenSyncDatabase.DATABASE_NAME,
        ).build()
}
