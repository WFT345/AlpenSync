// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

/**
 * What one contacts sync run did — every count the M2d debug surface shows,
 * plus the mass-delete-guard abort record. Pure data, no Android types, so
 * the app shell and unit tests consume it identically.
 *
 * Delete semantics at M2 (TombstoneLifecycle): a contact the server stops
 * listing becomes a tombstone ([tombstonedNow]) and its provider row stays
 * until the 24 h grace expires; [swept] counts the rows actually deleted
 * from the provider this run.
 */
data class SyncReport(
    /** Contacts the server metadata listing returned. */
    val listed: Int,

    /** Contacts fully fetched (new or ModifyTime advanced). */
    val fetched: Int,

    /** RawContacts created in the provider. */
    val inserted: Int,

    /** Existing RawContacts whose child rows were rewritten. */
    val updated: Int,

    /** No provider write needed (ModifyTime-skip + hash-equal after fetch). */
    val unchanged: Int,

    /** Freshly tombstoned this run (provider row still present). */
    val tombstonedNow: Int,

    /** Still inside the deletion grace period from an earlier run. */
    val tombstonedPending: Int,

    /** Expired tombstones whose provider row + mapping were deleted. */
    val swept: Int,

    /** Tombstones cancelled because the contact reappeared on the server. */
    val restored: Int,

    /** Name-only/note-only contacts — the accepted M2 coverage gap (ADR 0005 Q3). */
    val skippedNotSyncable: Int,

    /** Per-contact fetch/decrypt failures (mapping rows marked ERROR). */
    val contactErrors: Int,

    /** Individual cards dropped by the decrypter across all contacts. */
    val cardFailures: Int,

    /** Contacts written despite a missing/failing card signature (retain policy). */
    val unverifiedContacts: Int,

    /** Set iff the mass-delete guard aborted the run BEFORE any provider write. */
    val guardAbort: GuardAbort?,

    /** The run tracker's terminal phase (COMPLETED / ABORTED). */
    val phase: SyncRunPhase,
) {
    val succeeded: Boolean get() = phase == SyncRunPhase.COMPLETED && guardAbort == null
}

/** The guard's verdict payload, surfaced verbatim for the sync log. */
data class GuardAbort(val pendingDeletions: Int, val lastKnownTotal: Int)
