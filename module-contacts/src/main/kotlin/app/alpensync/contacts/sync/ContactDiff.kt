// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.TombstoneEntity

/** Why a mapped contact needs a provider rewrite. */
enum class ChangeReason {
    /** Any mapped field changed (photo may also have changed — a full child-row rewrite covers both). */
    CONTENT,

    /** Only the inline photo bytes changed; the writer can update just the Photo row. */
    PHOTO_ONLY,
}

/** Server contact with no mapping row — the writer creates a new RawContact. */
data class NewContact(
    val projected: ProjectedContact,
    val contentHash: String,
    val photoHash: String?,
)

/** Mapped contact whose hashes moved. [mapping] carries the provider raw-contact ID. */
data class ChangedContact(
    val mapping: ContactMapEntity,
    val projected: ProjectedContact,
    val contentHash: String,
    val photoHash: String?,
    val reason: ChangeReason,
)

/** Hashes still match — the engine refreshes bookkeeping timestamps only. */
data class UnchangedContact(val mapping: ContactMapEntity)

/**
 * Mapped contact the server no longer lists — a tombstone candidate. The
 * provider row stays in place until the grace period expires
 * (TombstoneLifecycle); this is NOT an immediate delete.
 */
data class DeletedContact(val mapping: ContactMapEntity)

/**
 * The differ's output: typed classification of one sync run's remote set
 * against the local mapping + tombstone snapshots. The engine turns these
 * into provider ops (M2d) and Room writes; nothing here touches Android or IO.
 */
class ContactDiff(
    val newContacts: List<NewContact>,
    val changedContacts: List<ChangedContact>,
    val unchangedContacts: List<UnchangedContact>,
    /** Vanished this run, no tombstone yet — create tombstones for these. */
    val deletedContacts: List<DeletedContact>,
    /** Vanished in an earlier run, grace period still running. */
    val stillTombstoned: List<TombstoneEntity>,
    /** Reappeared on the server while tombstoned — cancel those tombstones. */
    val restored: List<TombstoneEntity>,
    /**
     * Remote contacts that project but fail the syncable-fields guard
     * (name-only/note-only — ADR 0005 open question 3, accepted gap). Never
     * written; surfaced here only so the sync report can count them.
     */
    val skippedNotSyncable: List<ProjectedContact>,
) {
    /** Deletions that would take effect now — the mass-delete guard's input. */
    val pendingDeletionCount: Int get() = deletedContacts.size + stillTombstoned.size

    /** Idempotency contract: a no-op run produces an empty diff. */
    fun isNoOp(): Boolean =
        newContacts.isEmpty() && changedContacts.isEmpty() && deletedContacts.isEmpty() &&
            stillTombstoned.isEmpty() && restored.isEmpty()
}
