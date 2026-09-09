// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../BatchApplier.kt. Deviation: checked provider
// exceptions are wrapped in IOException so the sync engine + SyncAdapter see
// exactly one failure type for the IO class (SyncResult.numIoExceptions).

package app.alpensync.contacts.writer

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.OperationApplicationException
import android.os.RemoteException
import android.provider.ContactsContract.RawContacts
import java.io.IOException

/**
 * Owns the only `provider.applyBatch` call in the codebase (ADR 0005
 * Section 6 — no other module builds ContactsContract writes).
 *
 * Each chunk is one binder transaction; a chunk that fails leaves earlier
 * chunks committed (one contact may keep wiped child rows until the next
 * successful sync — the RawContact shell and aggregation state survive;
 * recorded in the ADR's risk register). The engine propagates the failure
 * and the SyncResult stats surface it.
 */
class BatchApplier(private val provider: ContentProviderClient) {

    @Throws(IOException::class)
    fun apply(account: Account, intents: List<RawContactOpIntent>): ApplyResult {
        if (intents.isEmpty()) return ApplyResult()
        val chunks = BatchPlanner.plan(account, intents)
        var totalResults = 0
        val createdRawIds = HashMap<String, Long>()
        for (chunk in chunks) {
            val results = applyChunk(chunk.ops)
            totalResults += results.size
            createdRawIds += harvestCreatedRawIds(chunk.creates, results)
        }
        return ApplyResult(totalOpsApplied = totalResults, createdRawIds = createdRawIds)
    }

    @Throws(IOException::class)
    private fun applyChunk(chunk: List<ContentProviderOperation>): Array<ContentProviderResult> = try {
        @Suppress("DEPRECATION")
        provider.applyBatch(ArrayList(chunk))
    } catch (e: RemoteException) {
        throw IOException("ContactsProvider applyBatch transport failure", e)
    } catch (e: OperationApplicationException) {
        throw IOException("ContactsProvider applyBatch rejected the batch", e)
    }

    /**
     * Deletes every RawContact this account owns (the logout wipe — M4 wires
     * it to account removal). Caller-IS-SYNCADAPTER is set so the rows don't
     * leave tombstones that would resurrect as duplicates on re-login.
     */
    @Throws(IOException::class)
    fun deleteAllForAccount(account: Account): Int {
        val uri = SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        return try {
            provider.delete(
                uri,
                "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
                arrayOf(account.type, account.name),
            )
        } catch (e: RemoteException) {
            throw IOException("ContactsProvider delete transport failure", e)
        }
    }
}

data class ApplyResult(
    val totalOpsApplied: Int = 0,
    /**
     * SOURCE_ID → provider-assigned RawContacts._ID for every CreateContact,
     * harvested from the applyBatch results. A create missing here means the
     * provider didn't echo a URI — the engine records that contact as
     * provider_write_missing instead of guessing an ID.
     */
    val createdRawIds: Map<String, Long> = emptyMap(),
)

/** Maps each chunk's create slots to the raw-contact ID the provider assigned (result URI's last segment). */
internal fun harvestCreatedRawIds(
    creates: List<CreateSlot>,
    results: Array<ContentProviderResult>,
): Map<String, Long> {
    if (creates.isEmpty()) return emptyMap()
    val out = HashMap<String, Long>(creates.size)
    for (slot in creates) {
        val rawId = results.getOrNull(slot.opIndex)?.uri?.lastPathSegment?.toLongOrNull() ?: continue
        out[slot.sourceId] = rawId
    }
    return out
}
