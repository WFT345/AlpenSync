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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Incremental persist: a killed first pull must keep what it already wrote. */
@RunWith(RobolectricTestRunner::class)
class ContactsSyncEnginePersistTest {

    private lateinit var db: AlpenSyncDatabase
    private lateinit var store: CanonicalVCardStore
    private lateinit var writer: FakeWriter
    private var listed: List<ContactMetadataDto> = emptyList()
    private var dtos: MutableMap<String, ContactDto> = mutableMapOf()
    private val fetchCalls = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = CanonicalVCardStore(db.canonicalVCardDao(), { it }, { it }, {})
        writer = FakeWriter()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun killed_mid_pull_keeps_already_applied_contacts_and_retries_the_rest() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        addContact("c2", "Bob", "bob@example.org")
        addContact("c3", "Cara", "cara@example.org")
        addContact("c4", "Dan", "dan@example.org")
        addContact("c5", "Eve", "eve@example.org")
        writer.appliesBeforeFail = 2

        val first = runCatching { newEngine().run() }
        assertTrue(first.exceptionOrNull() is IOException)
        assertEquals(setOf("c1", "c2"), writer.existing.keys)
        assertEquals(2, db.contactMapDao().countForAccount(ACCOUNT))
        assertNull("no sync-state row before a finished run", db.syncStateDao().get(ACCOUNT))

        writer.appliesBeforeFail = null
        fetchCalls.clear()
        val retry = newEngine().run()
        assertTrue(retry.succeeded)
        assertEquals(setOf("c1", "c2", "c3", "c4", "c5"), writer.existing.keys)
        assertEquals(5, db.contactMapDao().countForAccount(ACCOUNT))
        assertEquals(3, retry.inserted)
        assertEquals(5, db.syncStateDao().get(ACCOUNT)?.lastKnownTotal)
        assertTrue("c1" !in fetchCalls && "c2" !in fetchCalls)
    }

    @Test
    fun killed_first_pull_still_trips_the_guard_on_a_later_empty_listing() = runTest {
        repeat(15) { addContact("c$it", "Name $it", "n$it@example.org") }
        writer.appliesBeforeFail = 10
        runCatching { newEngine().run() }
        assertEquals(10, db.contactMapDao().countForAccount(ACCOUNT))

        // An API fault returns an empty listing: the deletable universe is the
        // 10 landed mappings, so all 10 pending is 100% — over the 50% line.
        listed = emptyList()
        writer.appliesBeforeFail = null
        val report = newEngine().run()

        assertEquals(GuardAbort(pendingDeletions = 10, deletableTotal = 10), report.guardAbort)
        assertEquals("every landed mapping survives", 10, db.contactMapDao().countForAccount(ACCOUNT))
        assertTrue("no tombstones created", db.tombstoneDao().listForAccount(ACCOUNT).isEmpty())
        assertEquals("no provider rows deleted", 10, writer.existing.size)
    }

    @Test
    fun progress_reports_listed_then_each_processed_contact() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        addContact("c2", "Bob", "bob@example.org")
        val seen = mutableListOf<SyncProgress>()
        val engine = newEngine()
        engine.tracker.onProgress = { seen += it }
        val report = engine.run()

        assertTrue(report.succeeded)
        assertTrue(seen.first().inFlight)
        assertTrue(seen.any { it.listed == 2 && it.processed == 0 && it.inFlight })
        assertTrue(seen.any { it.listed == 2 && it.processed == 1 && it.applied == 1 })
        assertTrue(seen.any { it.listed == 2 && it.processed == 2 && it.applied == 2 })
        val done = seen.last()
        assertEquals(false, done.inFlight)
        assertEquals(2, done.listed)
        assertEquals(2, done.processed)
        assertEquals(2, done.applied)
    }

    private fun addContact(id: String, name: String, email: String) {
        listed = listed + ContactMetadataDto(id = id, modifyTime = 1L)
        dtos[id] = ContactDto(
            id = id,
            modifyTime = 1L,
            cards = listOf(ContactCardDto(type = 0, data = vcard(name, email))),
        )
    }

    private fun newEngine() = ContactsSyncEngine(
        accountName = ACCOUNT,
        listMetadata = { listed },
        fetchContact = { id ->
            fetchCalls += id
            dtos.getValue(id)
        },
        decrypter = ContactDecrypter { throw CardDecryptException("unexpected crypto op") },
        writer = writer,
        stores = ContactsSyncStore(db, store),
        clock = { 1_000_000L },
    )

    private class FakeWriter : ContactsWriterGateway {
        val existing = mutableMapOf<String, Long>()
        var appliesBeforeFail: Int? = null
        private var nextRawId = 1_000L

        override fun readExistingRawIds(): Map<String, Long> = existing.toMap()

        override fun apply(intents: List<RawContactOpIntent>): ApplyResult {
            val remaining = appliesBeforeFail
            if (remaining != null) {
                if (remaining <= 0) throw IOException("simulated process kill")
                appliesBeforeFail = remaining - 1
            }
            val created = HashMap<String, Long>()
            intents.forEach { intent ->
                when (intent) {
                    is RawContactOpIntent.CreateContact -> {
                        val rawId = nextRawId++
                        existing[intent.projected.protonContactId] = rawId
                        created[intent.projected.protonContactId] = rawId
                    }
                    is RawContactOpIntent.UpdateContact -> Unit
                    is RawContactOpIntent.DeleteContact -> existing.remove(intent.sourceId)
                    is RawContactOpIntent.SetSourceId -> existing[intent.sourceId] = intent.rawContactId
                }
            }
            return ApplyResult(totalOpsApplied = intents.size, createdRawIds = created)
        }
    }

    private companion object {
        const val ACCOUNT = "default"

        fun vcard(name: String, email: String): String =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:$name\r\nEMAIL:$email\r\nEND:VCARD\r\n"
    }
}
