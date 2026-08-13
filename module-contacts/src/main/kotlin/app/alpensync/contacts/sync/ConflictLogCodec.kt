// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.ContactMerger.FieldConflict

/**
 * Encodes the merge engine's per-field conflicts into the
 * `conflict_copies.field_conflicts_json` cell (ADR 0006 Option B: a per-field
 * entry the user can review/export; the M4 sync-log viewer consumes it).
 *
 * The JSON carries field names from [ContactMerger.Fields] (a closed,
 * compile-time vocabulary) and sha-256 hashes of the conflicting values —
 * NEVER the values themselves (DATAFLOW.md: no decrypted contact content at
 * rest outside Keystore-wrapped blobs; the full losing vCard lives in the
 * row's Keystore-wrapped `payload_enc`). Because the alphabet is closed
 * (fixed field ids + hex + null), the encoder needs no string escaping —
 * there is no character that could need it.
 *
 * Shape: `[{"f":"emails","l":"<hex|null>","s":"<hex|null>"}, …]` where `l`
 * is the losing local value's hash and `s` the winning server value's.
 */
object ConflictLogCodec {

    fun encode(conflicts: List<FieldConflict>): String = conflicts.joinToString(
        prefix = "[",
        separator = ",",
        postfix = "]",
    ) { conflict ->
        buildString {
            append("{\"f\":\"").append(conflict.field).append("\",\"l\":")
            appendHash(conflict.localHash)
            append(",\"s\":")
            appendHash(conflict.serverHash)
            append("}")
        }
    }

    private fun StringBuilder.appendHash(hash: String?) {
        if (hash == null) append("null") else append("\"").append(hash).append("\"")
    }
}
