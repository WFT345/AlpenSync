// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.ACCOUNT
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.BASE_VCARD
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.markingEncryptOp
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.vcard
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.contacts.vcard.ContactDecrypter
import app.alpensync.contacts.vcard.ContactSerializer
import app.alpensync.contacts.vcard.CardDecryptException
import app.alpensync.contacts.writer.ApplyResult
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.DirtyContact
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.BulkDeleteRequest
import app.alpensync.core.api.dto.ContactCardDto
import app.alpensync.core.api.dto.ContactDto
import app.alpensync.core.api.dto.ContactMetadataDto
import app.alpensync.core.api.dto.CreateContactsRequest
import app.alpensync.core.api.dto.UpdateContactRequest
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The M3b run shape (ADR 0007 Section 1): detect → push → pull, in order, as
 * one run — the wiring the SyncAdapter drives. One local edit pushed and one
 * server create pulled in a single run is the two-way acceptance path.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsSyncRunnerTest {

    private lateinit var db: AlpenSyncDatabase
    private lateinit var store: CanonicalVCardStore
    private lateinit var writer: RecordingWriter
    private lateinit var api: FakeWriteApi
    private val callOrder = mutableListOf<String>()
    private var dirty: List<DirtyContact> = emptyList()
    private val localRows = mutableMapOf<Long, app.alpensync.contacts.vcard.ProjectedContact?>()
    private var listed: List<ContactMetadataDto> = emptyList()
    private var dtos: MutableMap<String, ContactDto> = mutableMapOf()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = CanonicalVCardStore(db.canonicalVCardDao(), { it }, { it }, {})
        writer = RecordingWriter()
        api = FakeWriteApi()
    }

    @After
    fun tearDown() = db.close()

    @Test fun a_run_detects_pushes_then_pulls_in_one_pass() = runTest {
        seedSyncedContactWithLocalEdit()
        // A second contact exists only on the server — the pull half lands it.
        listed = listed + ContactMetadataDto(id = "srv-new", modifyTime = 1L)
        dtos["srv-new"] = ContactDto(
            id = "srv-new",
            modifyTime = 1L,
            cards = listOf(ContactCardDto(type = 0, data = BASE_VCARD)),
        )

        val report = newRunner().run()

        assertEquals(listOf("scan", "update", "list"), callOrder)
        assertTrue("the local edit was pushed", api.updateRequests.single().cards[1].data.contains("+9-999"))
        assertTrue("outbox drained", db.outboxDao().findByContact(ACCOUNT, "pc-1").isEmpty())
        assertEquals(ContactMapEntity.Status.CLEAN, db.contactMapDao().findByProtonId(ACCOUNT, "pc-1")?.syncStatus)
        assertEquals(1, report.inserted)
        assertTrue(writer.existing.containsKey("srv-new"))
    }

    @Test fun a_detector_failure_aborts_before_any_push_or_pull() = runTest {
        seedSyncedContactWithLocalEdit()
        val failingRunner = ContactsSyncRunner(
            detector = LocalChangeDetector(
                accountName = ACCOUNT,
                stores = ContactsSyncStore(db, store),
                writer = writer,
                readDirty = {
                    callOrder += "scan"
                    throw IOException("provider dead")
                },
                readLocal = { rawId, _ -> localRows[rawId] },
                clearDirty = { },
            ),
            writeEngine = newWriteEngine(),
            pullEngine = newPullEngine(),
        )

        try {
            failingRunner.run()
            fail("expected the provider failure to propagate")
        } catch (expected: IOException) {
            // classified as IO by the adapter
        }
        assertTrue(api.calls.isEmpty())
        assertEquals(listOf("scan"), callOrder)
    }

    /** pc-1 is synced; the provider now holds an edit (an added phone) and the DIRTY flag is set. */
    private suspend fun seedSyncedContactWithLocalEdit() {
        val baseText = CanonicalVCardText.write(vcard(BASE_VCARD))
        store.write(ACCOUNT, "pc-1", baseText)
        val baseline = app.alpensync.contacts.vcard.ContactProjection.project(
            app.alpensync.contacts.vcard.CanonicalContact.ofVCard("pc-1", vcard(baseText)),
        )
        db.contactMapDao().upsert(
            ContactMapEntity(
                accountName = ACCOUNT,
                protonContactId = "pc-1",
                protonUid = "urn:uuid:pc-1",
                androidRawContactId = 7L,
                modifyTime = 1L,
                contentHash = ContactHasher.contentHash(baseline),
                photoHash = null,
                isVerified = true,
                syncStatus = ContactMapEntity.Status.CLEAN,
                lastError = null,
                lastSyncedAt = 1L,
                lastKnownServerPayloadHash = CanonicalVCardText.payloadHash(baseText),
            ),
        )
        localRows[7L] = localProjection("pc-1", "Alice Base", "+9-999")
        dirty = listOf(DirtyContact(7L, "pc-1", isDirty = true, isDeleted = false))
        api.fetchCanonicalHandler = { WriteEngineFixture.canonicalOf("pc-1", baseText) }
        // The pull must observe the push's new ModifyTime (43) and skip pc-1.
        listed = listOf(ContactMetadataDto(id = "pc-1", modifyTime = 43L))
    }

    private fun newRunner(): ContactsSyncRunner = ContactsSyncRunner(
        detector = LocalChangeDetector(
            accountName = ACCOUNT,
            stores = ContactsSyncStore(db, store),
            writer = writer,
            readDirty = { callOrder += "scan"; dirty },
            readLocal = { rawId, _ -> localRows[rawId] },
            clearDirty = { },
        ),
        writeEngine = newWriteEngine(),
        pullEngine = newPullEngine(),
    )

    /** Same fake API, but stamps `update` onto [callOrder] so the run-shape assertion can see the push. */
    private fun recordingApi(): ContactWriteApi = object : ContactWriteApi {
        override suspend fun create(request: CreateContactsRequest) = api.create(request)
        override suspend fun update(protonContactId: String, request: UpdateContactRequest) =
            api.update(protonContactId, request).also { callOrder += "update" }
        override suspend fun delete(request: BulkDeleteRequest) = api.delete(request)
        override suspend fun fetchCanonical(protonContactId: String) = api.fetchCanonical(protonContactId)
    }

    private fun newWriteEngine(): ContactWriteEngine = ContactWriteEngine(
        accountName = ACCOUNT,
        db = db,
        pusher = OutboxEntryPusher(
            accountName = ACCOUNT,
            stores = ContactsSyncStore(db, store),
            factory = ContactWriteFactory(ContactSerializer(markingEncryptOp)),
            api = recordingApi(),
            readLocal = { rawId, _ -> localRows[rawId] },
            writer = writer,
            clock = { 1_000_000L },
        ),
        clock = { 1_000_000L },
    )

    private fun newPullEngine(): ContactsSyncEngine = ContactsSyncEngine(
        accountName = ACCOUNT,
        listMetadata = { callOrder += "list"; listed },
        fetchContact = { id -> dtos.getValue(id) },
        decrypter = ContactDecrypter { throw CardDecryptException("unexpected crypto op") },
        writer = writer,
        stores = ContactsSyncStore(db, store),
        clock = { 1_000_000L },
    )

    /** Recording gateway with the provider's SOURCE_ID map semantics. */
    private class RecordingWriter : ContactsWriterGateway {
        val applied = mutableListOf<List<RawContactOpIntent>>()
        val existing = mutableMapOf<String, Long>()
        private var nextRawId = 1_000L

        override fun readExistingRawIds(): Map<String, Long> = existing.toMap()

        override fun apply(intents: List<RawContactOpIntent>): ApplyResult {
            applied += intents
            intents.forEach { intent ->
                when (intent) {
                    is RawContactOpIntent.CreateContact ->
                        existing[intent.projected.protonContactId] = nextRawId++
                    is RawContactOpIntent.UpdateContact -> Unit
                    is RawContactOpIntent.DeleteContact -> existing.remove(intent.sourceId)
                    is RawContactOpIntent.SetSourceId -> {
                        existing.entries.singleOrNull { it.value == intent.rawContactId }
                            ?.let { existing.remove(it.key) }
                        existing[intent.sourceId] = intent.rawContactId
                    }
                }
            }
            return ApplyResult()
        }
    }
}
