// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../BatchChunker.kt (BatchPlanner)

package app.alpensync.contacts.writer

import android.accounts.Account
import android.content.ContentProviderOperation

/**
 * Packs intent-derived ops into batches respecting both
 *   - the binder transaction limit (ADR 0005 Section 6 caps at 450 ops/batch), and
 *   - the back-reference contract: withValueBackReference indices are absolute
 *     to the assembled batch, so a Create intent's ops MUST land in one chunk,
 *     each chunk re-anchoring its RawContacts insert at the chunk's own offset.
 *
 * Implementation: each intent's ops are materialized with the current
 * batch-relative baseIdx; when adding them would overflow the chunk, the
 * chunk is closed and the intent re-built with baseIdx = 0.
 */
object BatchPlanner {

    const val MAX_OPS_PER_BATCH = 450

    fun plan(
        account: Account,
        intents: List<RawContactOpIntent>,
        maxOpsPerBatch: Int = MAX_OPS_PER_BATCH,
    ): List<PlannedChunk> {
        require(maxOpsPerBatch > 0) { "maxOpsPerBatch must be positive" }

        val chunks = ArrayList<PlannedChunk>()
        var current = ArrayList<ContentProviderOperation>()
        var creates = ArrayList<CreateSlot>()

        for (intent in intents) {
            var built = ContactsContractOps.build(account, intent, baseIdx = current.size)
            require(built.size <= maxOpsPerBatch) {
                "Single intent produced ${built.size} ops; exceeds maxOpsPerBatch=$maxOpsPerBatch"
            }
            if (current.size + built.size > maxOpsPerBatch && current.isNotEmpty()) {
                chunks += PlannedChunk(current, creates)
                current = ArrayList(maxOpsPerBatch)
                creates = ArrayList()
                // Re-anchor back-refs for the new chunk (cheap to redo always).
                built = ContactsContractOps.build(account, intent, baseIdx = 0)
            }
            // A create's RawContacts insert is always its first op — record
            // where it lands so the applier can read the assigned _ID back
            // from the applyBatch results instead of re-querying the provider.
            if (intent is RawContactOpIntent.CreateContact) {
                creates += CreateSlot(current.size, intent.projected.protonContactId)
            }
            current.addAll(built)
        }

        if (current.isNotEmpty()) chunks += PlannedChunk(current, creates)
        return chunks
    }
}

/** One applyBatch-ready chunk plus where each CreateContact's RawContacts insert landed in it. */
data class PlannedChunk(
    val ops: List<ContentProviderOperation>,
    val creates: List<CreateSlot>,
)

/** [opIndex] (chunk-relative) of the RawContacts insert that creates [sourceId]'s row. */
data class CreateSlot(val opIndex: Int, val sourceId: String)
