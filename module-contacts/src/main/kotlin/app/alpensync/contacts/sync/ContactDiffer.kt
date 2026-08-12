// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Classification shape informed by pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/contacts-writer/.../RawContactDiffer.kt (create/update/delete by
// source-ID set membership). Rewritten: hashes live here (pcontacts'
// caller pre-filters), deletes become grace-period tombstone candidates,
// and tombstone restore/still-pending are first-class outputs (M2c scope).

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.ContactProjection
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.TombstoneEntity

/**
 * Pure hash-based change detection: the M2b pipeline's canonical contacts
 * vs the Room mapping + tombstone snapshots. No fuzzy matching anywhere —
 * any mapped-field difference is a CONTENT change, photos compare by hash
 * only (research notes Section 3.3: "Bob" vs "Bob Smith" is a plain content
 * change; phones are verbatim).
 *
 * Change detection is by CONTENT HASH, not server ModifyTime: the engine
 * pre-fetches only contacts whose ModifyTime advanced (the two-tier skip),
 * but ModifyTime bumps without visible changes are common — the hash is the
 * source of truth, so those land in [ContactDiff.unchangedContacts] and cost
 * only a bookkeeping refresh.
 */
object ContactDiffer {

    /**
     * [serverListedIds] is the M2d engine's two-tier-skip input: the FULL set
     * of IDs the server listing returned, including contacts that were not
     * fetched this run (ModifyTime-skip) or whose fetch/decrypt failed.
     * Delete classification must run against that full set — otherwise every
     * unfetched mapping would masquerade as a server-side delete. When null
     * (the pure-pipeline tests), the reference set is derived from [remote]
     * exactly as before.
     */
    fun diff(
        remote: Collection<CanonicalContact>,
        mappings: List<ContactMapEntity>,
        tombstones: List<TombstoneEntity>,
        serverListedIds: Set<String>? = null,
    ): ContactDiff {
        val mappingsById = mappings.associateBy { it.protonContactId }
        val tombstonesById = tombstones.associateBy { it.protonContactId }

        val newContacts = mutableListOf<NewContact>()
        val changed = mutableListOf<ChangedContact>()
        val unchanged = mutableListOf<UnchangedContact>()
        val skipped = mutableListOf<ProjectedContact>()
        val restored = mutableListOf<TombstoneEntity>()
        val syncableIds = HashSet<String>(remote.size)

        for (canonical in remote) {
            val projected = ContactProjection.project(canonical)
            if (!projected.hasSyncableFields()) {
                skipped += projected
                continue
            }
            syncableIds += canonical.protonContactId
            tombstonesById[canonical.protonContactId]?.let { restored += it }
            classify(projected, mappingsById[canonical.protonContactId], newContacts, changed, unchanged)
        }

        val deleteReferenceIds = if (serverListedIds == null) {
            syncableIds
        } else {
            val skippedIds = skipped.mapTo(HashSet()) { it.protonContactId }
            serverListedIds - skippedIds
        }
        val (deleted, stillTombstoned) = classifyDeletes(deleteReferenceIds, mappings, tombstones, tombstonesById)
        return ContactDiff(newContacts, changed, unchanged, deleted, stillTombstoned, restored, skipped)
    }

    private fun classify(
        projected: ProjectedContact,
        mapping: ContactMapEntity?,
        newContacts: MutableList<NewContact>,
        changed: MutableList<ChangedContact>,
        unchanged: MutableList<UnchangedContact>,
    ) {
        val contentHash = ContactHasher.contentHash(projected)
        val photoHash = ContactHasher.photoHash(projected)
        if (mapping == null) {
            newContacts += NewContact(projected, contentHash, photoHash)
            return
        }
        when {
            mapping.contentHash != contentHash ->
                changed += ChangedContact(mapping, projected, contentHash, photoHash, ChangeReason.CONTENT)
            mapping.photoHash != photoHash ->
                changed += ChangedContact(mapping, projected, contentHash, photoHash, ChangeReason.PHOTO_ONLY)
            else -> unchanged += UnchangedContact(mapping)
        }
    }

    /**
     * Anything we mapped that the server no longer lists: a fresh delete (no
     * tombstone yet) or one already inside its grace period (still pending).
     */
    private fun classifyDeletes(
        syncableIds: Set<String>,
        mappings: List<ContactMapEntity>,
        tombstones: List<TombstoneEntity>,
        tombstonesById: Map<String, TombstoneEntity>,
    ): Pair<List<DeletedContact>, List<TombstoneEntity>> {
        val deleted = mutableListOf<DeletedContact>()
        val stillTombstoned = mutableListOf<TombstoneEntity>()
        for (mapping in mappings) {
            if (mapping.protonContactId !in syncableIds && mapping.protonContactId !in tombstonesById) {
                deleted += DeletedContact(mapping)
            }
        }
        for (tombstone in tombstones) {
            if (tombstone.protonContactId !in syncableIds) {
                stillTombstoned += tombstone
            }
        }
        return deleted to stillTombstoned
    }
}
