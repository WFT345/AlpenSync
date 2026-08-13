// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Shape informed by pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts
// @ bf9b0c5, path core/sync/.../contacts/WriteReport.kt — extended with the
// conflict + grace-skip counters our ADR 0006/0007 semantics need.

package app.alpensync.contacts.sync

/**
 * What one outbox drain did (ADR 0007 Section 4). Pure data; the counts feed
 * tests now and the M4 sync-log surface later. [pushed] counts the entries
 * whose server write succeeded this run.
 */
data class WriteReport(
    /** CREATE entries pushed; the mapping was re-keyed to the server ID. */
    val created: Int = 0,

    /** UPDATE entries pushed (merged or clean). */
    val updated: Int = 0,

    /** DELETE entries pushed (or already fulfilled locally). */
    val deleted: Int = 0,

    /** Both-sides field conflicts resolved server-wins; each has a conflict_copies row. */
    val conflicts: Int = 0,

    /** Entries that failed retryably (backoff armed via next_attempt_at). */
    val retried: Int = 0,

    /** Entries side-lined for user requeue/discard (permanent failure). */
    val quarantined: Int = 0,

    /** DELETE entries still inside the 1-hour grace window. */
    val skippedGrace: Int = 0,
) {
    val pushed: Int get() = created + updated + deleted

    operator fun plus(other: WriteReport): WriteReport = WriteReport(
        created = created + other.created,
        updated = updated + other.updated,
        deleted = deleted + other.deleted,
        conflicts = conflicts + other.conflicts,
        retried = retried + other.retried,
        quarantined = quarantined + other.quarantined,
        skippedGrace = skippedGrace + other.skippedGrace,
    )
}
