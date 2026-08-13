// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Algorithm skeleton adapted from pcontacts (GPL-3.0),
// https://github.com/andreabenetton/pcontacts @ bf9b0c5, path
// core/sync/.../contacts/merge/ThreeWayMerger.kt (scalar + keyed-set rules).
// Deviations (ADR 0006 Accepted Option B): the base is the REAL stored
// canonical vCard, not pcontacts' empty-base approximation; a
// both-sides-changed-same-field conflict resolves to the SERVER value
// deterministically (theirs keeps local + waits for a user pick there) with
// the losing local value preserved via conflict_copies; inputs/outputs are
// canonical ez-vcard VCards so unmapped properties (X-*, BDAY, …) ride along
// losslessly. pcontacts' "added on both with different values → keep both"
// duplicate-key manufacturing is rejected: the same key added on both sides
// with different values is a conflict, server wins.

package app.alpensync.contacts.vcard

import app.alpensync.contacts.vcard.CanonicalVCardEditor.PhotoUpdate
import ezvcard.VCard

/**
 * The ADR 0006 Option-B three-way merge. Inputs:
 *   - [base] — the stored canonical vCard (the last-synced shared ancestor);
 *   - [ours] — base + the local provider edits applied (the write path's
 *     update candidate), so `ours` can differ from [base] ONLY in projected
 *     fields;
 *   - [theirs] — the freshly pulled server state.
 *
 * Resolution per field (scalars) / per keyed entry (multi-value families):
 * unchanged on one side → take the changed side; changed to the same value
 * on both → take it; changed differently on both → **server wins** and the
 * local value is recorded as a [FieldConflict] (hashes only — content never
 * lands in a log-shaped structure; the full losing vCard is preserved
 * Keystore-wrapped in conflict_copies by the caller). Unmapped properties
 * merge trivially: `ours` == `base` for them by construction, so the merged
 * vCard carries [theirs]' versions verbatim via [CanonicalVCardEditor]'s
 * pass-through.
 *
 * Pure JVM, no Android imports — the merge matrix tests drive it directly.
 */
object ContactMerger {

    /**
     * One both-sides-changed-same-field record. [localHash]/[serverHash] are
     * sha-256 of the losing/winning value's string form (null = that side
     * deleted the field) — enough for the M4 sync-log surface to match
     * against the preserved payload, never plaintext (DATAFLOW.md).
     */
    data class FieldConflict(
        val field: String,
        val localHash: String?,
        val serverHash: String?,
    )

    data class MergeOutcome(
        /** The merged canonical vCard — serialize for the push, persist to the canonical store. */
        val merged: VCard,

        /** The honest projection of [merged] (post-rebuild) — provider rows + mapping hashes key off it. */
        val projection: ProjectedContact,

        val conflicts: List<FieldConflict>,
    ) {
        /** True when nothing needed the server-wins rule (the common case — no conflict at all). */
        val autoMerged: Boolean get() = conflicts.isEmpty()
    }

    fun merge(protonContactId: String, base: VCard, ours: VCard, theirs: VCard): MergeOutcome {
        val baseP = project(protonContactId, base)
        val oursP = project(protonContactId, ours)
        val theirsP = project(protonContactId, theirs)
        val conflicts = mutableListOf<FieldConflict>()

        val mergedProjection = theirsP.copy(
            displayName = scalar(Fields.FN, baseP.displayName, oursP.displayName, theirsP.displayName, conflicts),
            structuredName = scalar(
                Fields.N, baseP.structuredName, oursP.structuredName, theirsP.structuredName, conflicts,
            ),
            emails = keyed(Fields.EMAILS, baseP.emails, oursP.emails, theirsP.emails, conflicts, key = { it.address }),
            phones = keyed(Fields.PHONES, baseP.phones, oursP.phones, theirsP.phones, conflicts, key = { it.number }),
            addresses = keyed(
                Fields.ADDRESSES, baseP.addresses, oursP.addresses, theirsP.addresses, conflicts, ::addressKey,
            ),
            organization =
                scalar(Fields.ORGANIZATION, baseP.organization, oursP.organization, theirsP.organization, conflicts),
            notes = scalar(Fields.NOTES, baseP.notes, oursP.notes, theirsP.notes, conflicts),
            imAccounts = keyed(
                Fields.IM_ACCOUNTS, baseP.imAccounts, oursP.imAccounts, theirsP.imAccounts, conflicts, ::imKey,
            ),
            urls = keyed(Fields.URLS, baseP.urls, oursP.urls, theirsP.urls, conflicts, key = { it }),
            birthday = scalar(Fields.BIRTHDAY, baseP.birthday, oursP.birthday, theirsP.birthday, conflicts),
            anniversary = scalar(
                Fields.ANNIVERSARY, baseP.anniversary, oursP.anniversary, theirsP.anniversary, conflicts,
            ),
            photo = scalar(Fields.PHOTO, baseP.photo, oursP.photo, theirsP.photo, conflicts, ::photoSummary),
        )
        val merged = CanonicalVCardEditor.applyEdits(theirs, mergedProjection, photoUpdate(mergedProjection, theirsP))
        return MergeOutcome(merged, project(protonContactId, merged), conflicts)
    }

    /**
     * Server-wins scalar rule. Nulls are ordinary values here, so
     * delete-vs-edit needs no special casing: a side that deleted the field
     * simply compares as null (e.g. base=X, ours=Y, theirs=null → conflict,
     * the deletion wins).
     */
    private fun <T> scalar(
        field: String,
        base: T,
        ours: T,
        theirs: T,
        conflicts: MutableList<FieldConflict>,
        summary: (T & Any) -> String = { it.toString() },
    ): T {
        if (theirs == base) return ours
        if (ours == base || ours == theirs) return theirs
        conflicts += conflict(field, ours, theirs, summary)
        return theirs
    }

    /**
     * Keyed-set merge over one multi-value family. Iteration is theirs-first
     * so a locally-untouched family reproduces the server list exactly and
     * [CanonicalVCardEditor] passes the original property objects through.
     */
    private fun <T : Any, K> keyed(
        field: String,
        base: List<T>,
        ours: List<T>,
        theirs: List<T>,
        conflicts: MutableList<FieldConflict>,
        key: (T) -> K,
        summary: (T) -> String = { it.toString() },
    ): List<T> {
        val baseByKey = base.associateBy(key)
        val oursByKey = ours.associateBy(key)
        val theirsByKey = theirs.associateBy(key)
        val orderedKeys = LinkedHashSet<K>().apply {
            addAll(theirsByKey.keys)
            addAll(oursByKey.keys)
            addAll(baseByKey.keys)
        }
        val result = mutableListOf<T>()
        for (k in orderedKeys) {
            when (val action = resolveEntry(baseByKey[k], oursByKey[k], theirsByKey[k])) {
                is EntryAction.Keep -> result += action.entry
                is EntryAction.Conflict -> {
                    conflicts += conflict(field, action.local, action.server, summary)
                    action.server?.let { result += it }
                }
                EntryAction.Drop -> Unit
            }
        }
        return result
    }

    /** The per-entry decision table, split at the null pattern to stay auditable. */
    private fun <T : Any> resolveEntry(base: T?, ours: T?, theirs: T?): EntryAction<T> {
        if (base == null) return resolveAddition(ours, theirs)
        if (theirs == null) return resolveServerDelete(base, ours)
        if (ours == null) return resolveLocalDelete(base, theirs)
        return when {
            theirs == base -> EntryAction.Keep(ours)
            ours == base -> EntryAction.Keep(theirs)
            ours == theirs -> EntryAction.Keep(theirs)
            else -> EntryAction.Conflict(ours, theirs)
        }
    }

    /**
     * Server deleted the entry: honor it when local is unchanged; a local
     * edit conflicts and loses (the deletion stands).
     */
    private fun <T : Any> resolveServerDelete(base: T, ours: T?): EntryAction<T> =
        if (ours == null || ours == base) EntryAction.Drop else EntryAction.Conflict(ours, null)

    /**
     * Local deleted the entry: honor it when the server is unchanged; a
     * server edit wins and the deletion is the recorded loser.
     */
    private fun <T : Any> resolveLocalDelete(base: T, theirs: T): EntryAction<T> =
        if (theirs == base) EntryAction.Drop else EntryAction.Conflict(null, theirs)

    /** [base] lacks the key: added on one side, or on both (same value → take it; else conflict). */
    private fun <T : Any> resolveAddition(ours: T?, theirs: T?): EntryAction<T> = when {
        ours != null && theirs != null ->
            if (ours == theirs) EntryAction.Keep(theirs) else EntryAction.Conflict(ours, theirs)
        ours != null -> EntryAction.Keep(ours)
        theirs != null -> EntryAction.Keep(theirs)
        else -> EntryAction.Drop
    }

    private fun <T> conflict(
        field: String,
        local: T?,
        server: T?,
        summary: (T & Any) -> String,
    ): FieldConflict = FieldConflict(
        field = field,
        localHash = local?.let { hashOf(summary(it)) },
        serverHash = server?.let { hashOf(summary(it)) },
    )

    private fun photoUpdate(merged: ProjectedContact, theirs: ProjectedContact): PhotoUpdate = when {
        merged.photo == theirs.photo -> PhotoUpdate.KEEP_SERVER_BYTES
        merged.photo == null -> PhotoUpdate.REMOVE
        else -> PhotoUpdate.REPLACE_FROM_PROJECTION
    }

    private fun project(protonContactId: String, vcard: VCard): ProjectedContact =
        ContactProjection.project(
            CanonicalContact(
                protonContactId = protonContactId,
                vcard = vcard,
                protonUid = vcard.uid?.value?.takeIf { it.isNotBlank() },
                verified = true,
                cardCount = 0,
                unverifiedCardCount = 0,
                malformedFragmentCount = 0,
            ),
        )

    private fun addressKey(address: ProjectedAddress): String = listOf(
        address.poBox, address.extendedAddress, address.street, address.locality,
        address.region, address.postalCode, address.country,
    ).joinToString(UNIT_SEPARATOR) { it.orEmpty() }

    private fun imKey(im: ProjectedIm): String = im.handle + UNIT_SEPARATOR + im.protocol.orEmpty()

    private fun photoSummary(photo: ProjectedPhoto): String = sha256Hex(photo.data)

    private fun hashOf(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    private sealed interface EntryAction<out T> {
        /** Take [entry]; no conflict. */
        data class Keep<T>(val entry: T) : EntryAction<T>

        /** Both sides changed the same field differently: server wins; record the loser. */
        data class Conflict<T>(val local: T?, val server: T?) : EntryAction<T>

        /** The entry is gone in the merged result (deleted where the other side didn't object). */
        data object Drop : EntryAction<Nothing>
    }

    /** Field identifiers for conflict rows — a closed, log-safe vocabulary (never content). */
    object Fields {
        const val FN = "fn"
        const val N = "n"
        const val EMAILS = "emails"
        const val PHONES = "phones"
        const val ADDRESSES = "addresses"
        const val ORGANIZATION = "organization"
        const val NOTES = "notes"
        const val IM_ACCOUNTS = "im_accounts"
        const val URLS = "urls"
        const val BIRTHDAY = "birthday"
        const val ANNIVERSARY = "anniversary"
        const val PHOTO = "photo"
    }

    private const val UNIT_SEPARATOR = "\u001F"
}
