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
        for (chunk in chunks) {
            totalResults += applyChunk(chunk)
        }
        return ApplyResult(totalOpsApplied = totalResults)
    }

    @Throws(IOException::class)
    private fun applyChunk(chunk: List<ContentProviderOperation>): Int = try {
        @Suppress("DEPRECATION")
        val results: Array<ContentProviderResult> = provider.applyBatch(ArrayList(chunk))
        results.size
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

data class ApplyResult(val totalOpsApplied: Int = 0)
