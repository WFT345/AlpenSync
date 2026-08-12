// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.writer

import android.accounts.Account
import android.content.ContentProviderClient
import java.io.IOException

/**
 * The engine-facing seam of the writer package: everything the sync engine
 * needs from ContactsProvider, nothing more. The production implementation
 * binds [RawContactReader] + [BatchApplier] to one account; engine unit
 * tests substitute an in-memory fake (task: "orchestrator logic with faked
 * pipeline stages").
 */
interface ContactsWriterGateway {
    /** SOURCE_ID → RawContacts._ID for every RawContact this account owns. */
    @Throws(IOException::class)
    fun readExistingRawIds(): Map<String, Long>

    /** Applies the intents in ≤450-op chunks; throws IOException on failure. */
    @Throws(IOException::class)
    fun apply(intents: List<RawContactOpIntent>): ApplyResult
}

/** Production gateway: the real ContactsProvider, one account. */
class ContactsProviderGateway(
    private val account: Account,
    provider: ContentProviderClient,
) : ContactsWriterGateway {
    private val reader = RawContactReader(provider)
    private val applier = BatchApplier(provider)

    override fun readExistingRawIds(): Map<String, Long> = reader.readExisting(account)

    override fun apply(intents: List<RawContactOpIntent>): ApplyResult = applier.apply(account, intents)
}
