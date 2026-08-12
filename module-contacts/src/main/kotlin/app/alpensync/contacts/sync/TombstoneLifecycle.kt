// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.core.db.entity.TombstoneEntity

/**
 * Tombstone lifecycle for grace-period deletes (M2c scope):
 *
 *  - CREATE: the server stops listing a mapped contact → [create]; the
 *    provider row and mapping row stay in place.
 *  - RESTORE: the contact reappears before expiry → the differ reports it in
 *    [ContactDiff.restored] and the engine simply deletes the tombstone —
 *    nothing was ever rewritten.
 *  - EXPIRE: [expired] selects tombstones whose grace period has run out;
 *    the engine deletes those provider rows + mappings, then sweeps the
 *    tombstones.
 *
 * All functions are pure; the clock is injected.
 */
object TombstoneLifecycle {

    /** 24 h — no upstream precedent (pcontacts' 1 h grace covers the M3 PUSH direction). */
    const val DEFAULT_GRACE_PERIOD_MS: Long = 24L * 60 * 60 * 1000

    fun create(
        deleted: DeletedContact,
        nowMs: Long,
        gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    ): TombstoneEntity {
        val mapping = deleted.mapping
        return TombstoneEntity(
            accountName = mapping.accountName,
            protonContactId = mapping.protonContactId,
            androidRawContactId = mapping.androidRawContactId,
            deletedAt = nowMs,
            expiresAt = nowMs + gracePeriodMs,
        )
    }

    /** Tombstones whose grace period has elapsed at [nowMs] (expiry is inclusive). */
    fun expired(tombstones: List<TombstoneEntity>, nowMs: Long): List<TombstoneEntity> =
        tombstones.filter { it.expiresAt <= nowMs }
}
