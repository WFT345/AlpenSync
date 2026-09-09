// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.vcard.CardDecryptException
import app.alpensync.contacts.vcard.ContactDecrypter
import app.alpensync.contacts.writer.ApplyResult
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.ContactCardDto
import app.alpensync.core.api.dto.ContactDto
import app.alpensync.core.api.dto.ContactMetadataDto
import app.alpensync.core.db.AlpenSyncDatabase
import java.io.IOException
import org.junit.After
import org.junit.Before

/**
 * Shared fixture for the pull-engine orchestration tests: every external
 * stage faked (listing, fetch, crypto, provider) and a real in-memory Room
 * DB. CLEAR_TEXT cards keep the decrypter on its no-crypto path; the
 * "crypto" lambda only exists to fail cards on demand.
 */
abstract class ContactsSyncEngineTestBase {

    protected lateinit var db: AlpenSyncDatabase
    protected lateinit var store: CanonicalVCardStore
    protected lateinit var writer: FakeWriter
    protected var now: Long = 1_000_000L
    protected var listed: List<ContactMetadataDto> = emptyList()
    protected var dtos: MutableMap<String, ContactDto> = mutableMapOf()
    protected var failingFetches: Set<String> = emptySet()
    protected var decrypter = ContactDecrypter { throw CardDecryptException("unexpected crypto op") }
    protected val fetchCalls = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        // Identity wrap: the store's crypto is CanonicalVCardStoreTest's
        // concern; here it only needs to persist and return text.
        store = CanonicalVCardStore(db.canonicalVCardDao(), { it }, { it }, {})
        writer = FakeWriter()
    }

    @After
    fun tearDown() = db.close()

    protected fun addContact(id: String, name: String, email: String) {
        listed = listed + meta(id)
        dtos[id] = ContactDto(id = id, modifyTime = 1L, cards = listOf(clearCard(vcard(name, email))))
    }

    protected fun newEngine() = ContactsSyncEngine(
        accountName = ACCOUNT,
        listMetadata = { listed },
        fetchContact = { id ->
            fetchCalls += id
            if (id in failingFetches) throw IOException("simulated network failure")
            dtos.getValue(id)
        },
        decrypter = decrypter,
        writer = writer,
        stores = ContactsSyncStore(db, store),
        clock = { now },
    )

    /** Recording gateway with the provider's SOURCE_ID map semantics. */
    protected class FakeWriter : ContactsWriterGateway {
        val applied = mutableListOf<List<RawContactOpIntent>>()
        val existing = mutableMapOf<String, Long>()
        private var nextRawId = 1_000L

        override fun readExistingRawIds(): Map<String, Long> = existing.toMap()

        override fun apply(intents: List<RawContactOpIntent>): ApplyResult {
            applied += intents
            val created = HashMap<String, Long>()
            intents.forEach { intent ->
                when (intent) {
                    is RawContactOpIntent.CreateContact -> {
                        val rawId = nextRawId++
                        existing[intent.projected.protonContactId] = rawId
                        created[intent.projected.protonContactId] = rawId
                    }
                    is RawContactOpIntent.UpdateContact -> Unit // raw row keeps its ID
                    is RawContactOpIntent.DeleteContact -> existing.remove(intent.sourceId)
                    is RawContactOpIntent.SetSourceId -> {
                        // The provider re-keys the row: old (possibly null) SOURCE_ID → the stamped
                        // one. A locally-created row exists provider-side even with a null
                        // SOURCE_ID, so it is absent from this map until stamped.
                        existing.entries.singleOrNull { it.value == intent.rawContactId }
                            ?.let { existing.remove(it.key) }
                        existing[intent.sourceId] = intent.rawContactId
                    }
                }
            }
            return ApplyResult(totalOpsApplied = intents.size, createdRawIds = created)
        }
    }

    companion object {
        const val ACCOUNT = "default"

        fun meta(id: String, modifyTime: Long = 1L) = ContactMetadataDto(id = id, modifyTime = modifyTime)

        fun vcard(name: String, email: String): String =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:$name\r\nEMAIL:$email\r\nEND:VCARD\r\n"

        fun clearCard(data: String) = ContactCardDto(type = 0, data = data)
    }
}
