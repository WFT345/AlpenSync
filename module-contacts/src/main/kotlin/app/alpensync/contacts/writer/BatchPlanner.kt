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
    ): List<List<ContentProviderOperation>> {
        require(maxOpsPerBatch > 0) { "maxOpsPerBatch must be positive" }

        val chunks = ArrayList<List<ContentProviderOperation>>()
        var current = ArrayList<ContentProviderOperation>()

        for (intent in intents) {
            var built = ContactsContractOps.build(account, intent, baseIdx = current.size)
            require(built.size <= maxOpsPerBatch) {
                "Single intent produced ${built.size} ops; exceeds maxOpsPerBatch=$maxOpsPerBatch"
            }
            if (current.size + built.size > maxOpsPerBatch && current.isNotEmpty()) {
                chunks += current
                current = ArrayList(maxOpsPerBatch)
                // Re-anchor back-refs for the new chunk (cheap to redo always).
                built = ContactsContractOps.build(account, intent, baseIdx = 0)
            }
            current.addAll(built)
        }

        if (current.isNotEmpty()) chunks += current
        return chunks
    }
}
