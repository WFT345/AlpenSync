// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.contacts.vcard.CardCryptoOutcome
import app.alpensync.contacts.vcard.CardCryptoRequest
import app.alpensync.contacts.vcard.CardDecryptException
import app.alpensync.contacts.vcard.ContactDecrypter
import app.alpensync.contacts.writer.ApplyResult
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.ContactCardDto
import app.alpensync.core.api.dto.ContactDto
import app.alpensync.core.api.dto.ContactMetadataDto
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.OutboxEntity
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Orchestration tests for the M2d sync engine with every external stage
 * faked (listing, fetch, crypto, provider) and a real in-memory Room DB.
 * CLEAR_TEXT cards keep the decrypter on its no-crypto path; the "crypto"
 * lambda only exists to fail cards on demand.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsSyncEngineTest {

    private lateinit var db: AlpenSyncDatabase
    private lateinit var store: CanonicalVCardStore
    private lateinit var writer: FakeWriter
    private var now: Long = 1_000_000L
    private var listed: List<ContactMetadataDto> = emptyList()
    private var dtos: MutableMap<String, ContactDto> = mutableMapOf()
    private var failingFetches: Set<String> = emptySet()
    private var decrypter = ContactDecrypter { throw CardDecryptException("unexpected crypto op") }
    private val fetchCalls = mutableListOf<String>()

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

    @Test
    fun first_sync_writes_everything_and_records_mappings_and_state() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        addContact("c2", "Bob", "bob@example.org")
        val report = newEngine().run()

        assertEquals(2, report.listed)
        assertEquals(2, report.inserted)
        assertEquals(0, report.unchanged)
        assertTrue(report.succeeded)
        val mapping = db.contactMapDao().findByProtonId(ACCOUNT, "c1")
        assertNotNull(mapping)
        assertEquals(ContactMapEntity.Status.CLEAN, mapping?.syncStatus)
        assertEquals(true, mapping?.isVerified)
        assertEquals(setOf("c1", "c2"), writer.existing.keys)
        assertEquals(2, db.syncStateDao().get(ACCOUNT)?.lastKnownTotal)
    }

    @Test
    fun second_identical_run_fetches_nothing_and_writes_nothing() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()
        fetchCalls.clear()
        writer.applied.clear()

        val report = newEngine().run()
        assertTrue("no provider ops expected", writer.applied.isEmpty())
        assertTrue("no per-ID fetches expected", fetchCalls.isEmpty())
        assertEquals(1, report.unchanged)
        assertEquals(0, report.inserted)
    }

    @Test
    fun per_contact_fetch_failure_is_counted_and_never_blocks_the_others() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        addContact("c2", "Bob", "bob@example.org")
        failingFetches = setOf("c2")

        val report = newEngine().run()
        assertEquals(1, report.inserted)
        assertEquals(1, report.contactErrors)
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "c2"))

        // Next run retries the failed contact (it has no mapping → re-fetch).
        failingFetches = emptySet()
        val second = newEngine().run()
        assertEquals(1, second.inserted)
        assertEquals(0, second.contactErrors)
        assertNotNull(db.contactMapDao().findByProtonId(ACCOUNT, "c2"))
    }

    @Test
    fun card_failure_marks_the_mapping_error_and_never_deletes_the_row() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()
        val rawId = writer.existing.getValue("c1")
        writer.applied.clear()

        // ModifyTime advances; the fetched contact now carries a card that fails decrypt.
        listed = listOf(meta("c1", modifyTime = 2L))
        dtos["c1"] = ContactDto(
            id = "c1",
            modifyTime = 2L,
            cards = listOf(ContactCardDto(type = 1, data = "-----BEGIN PGP MESSAGE-----")),
        )
        val report = newEngine().run()

        assertEquals(1, report.contactErrors)
        assertEquals(1, report.cardFailures)
        val mapping = db.contactMapDao().findByProtonId(ACCOUNT, "c1")
        assertEquals(ContactMapEntity.Status.ERROR, mapping?.syncStatus)
        assertEquals("card_failures", mapping?.lastError)
        assertEquals("provider row must survive a decrypt failure", rawId, writer.existing["c1"])
        assertTrue("no provider writes at all for the failed contact", writer.applied.isEmpty())
    }

    @Test
    fun mass_delete_guard_aborts_before_any_apply_and_records_the_abort() = runTest {
        repeat(10) { addContact("c$it", "Name $it", "n$it@example.org") }
        newEngine().run()
        writer.applied.clear()

        // The server listing suddenly shrinks to 4 of 10 → 6 pending deletes.
        listed = (0..3).map { meta("c$it") }
        val engine = newEngine()
        val report = engine.run()

        assertEquals(SyncRunPhase.ABORTED, engine.tracker.phase)
        assertEquals(GuardAbort(pendingDeletions = 6, lastKnownTotal = 10), report.guardAbort)
        assertTrue("guard abort must skip ALL provider writes", writer.applied.isEmpty())
        assertEquals("no tombstones created on abort", 0, db.tombstoneDao().listForAccount(ACCOUNT).size)
        assertEquals("last_known_total must not move on abort", 10, db.syncStateDao().get(ACCOUNT)?.lastKnownTotal)
        assertEquals("every mapping survives", 10, db.contactMapDao().countForAccount(ACCOUNT))
    }

    @Test
    fun remote_delete_tombstones_then_sweeps_after_the_grace_period() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()

        listed = emptyList()
        val deleteRun = newEngine().run()
        assertEquals(1, deleteRun.tombstonedNow)
        assertEquals("provider row stays during grace", setOf("c1"), writer.existing.keys)
        assertEquals(1, db.tombstoneDao().listForAccount(ACCOUNT).size)
        assertNotNull("mapping stays during grace", db.contactMapDao().findByProtonId(ACCOUNT, "c1"))

        now += TombstoneLifecycle.DEFAULT_GRACE_PERIOD_MS + 1
        val sweepRun = newEngine().run()
        assertEquals(1, sweepRun.swept)
        assertTrue("provider row deleted on expiry", writer.existing.isEmpty())
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "c1"))
        assertTrue(db.tombstoneDao().listForAccount(ACCOUNT).isEmpty())
    }

    @Test
    fun a_reappearing_contact_cancels_its_tombstone_without_a_rewrite() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()
        val rawId = writer.existing.getValue("c1")

        listed = emptyList()
        newEngine().run() // tombstoned
        listed = listOf(meta("c1")) // reappears within grace, same ModifyTime
        val report = newEngine().run()

        assertEquals(1, report.restored)
        assertTrue(db.tombstoneDao().listForAccount(ACCOUNT).isEmpty())
        assertEquals("same provider row, never rewritten", rawId, writer.existing["c1"])
        assertEquals(ContactMapEntity.Status.CLEAN, db.contactMapDao().findByProtonId(ACCOUNT, "c1")?.syncStatus)
    }

    @Test
    fun write_pending_contact_is_skipped_not_fetched_not_rewritten() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()
        writer.applied.clear()
        fetchCalls.clear()

        // A local edit is queued (PENDING_PUSH) while the server side moved.
        db.contactMapDao().markPendingPush(ACCOUNT, "c1")
        listed = listOf(meta("c1", modifyTime = 2L))
        dtos["c1"] = ContactDto(id = "c1", modifyTime = 2L, cards = listOf(clearCard(vcard("Alice S", "s@example.org"))))
        val report = newEngine().run()

        assertTrue("push-side owns the convergence — no fetch", fetchCalls.isEmpty())
        assertTrue("no provider rewrite of a write-pending contact", writer.applied.isEmpty())
        assertEquals(
            ContactMapEntity.Status.PENDING_PUSH,
            db.contactMapDao().findByProtonId(ACCOUNT, "c1")?.syncStatus,
        )
        assertEquals(0, report.updated)
    }

    @Test
    fun write_pending_contact_is_not_delete_classified_when_unlisted() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()
        db.contactMapDao().markPendingPush(ACCOUNT, "c1")
        writer.applied.clear()

        listed = emptyList()
        newEngine().run()

        assertTrue("no tombstone while a local edit is queued", db.tombstoneDao().listForAccount(ACCOUNT).isEmpty())
        assertNotNull(db.contactMapDao().findByProtonId(ACCOUNT, "c1"))
        assertTrue(writer.applied.isEmpty())
    }

    @Test
    fun applied_contact_persists_canonical_store_row_and_server_payload_hash() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()

        val stored = store.read(ACCOUNT, "c1")
        assertNotNull("canonical vCard persisted (encrypted store)", stored)
        val mapping = db.contactMapDao().findByProtonId(ACCOUNT, "c1")
        assertEquals(
            CanonicalVCardText.payloadHash(checkNotNull(stored)),
            mapping?.lastKnownServerPayloadHash,
        )
    }

    @Test
    fun hash_equal_refetch_backfills_a_missing_canonical_row() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()
        // Simulate an M2-era row: no canonical store row, no payload hash.
        store.delete(ACCOUNT, "c1")
        db.contactMapDao().updateServerPayloadHash(ACCOUNT, "c1", null)

        listed = listOf(meta("c1", modifyTime = 2L)) // ModifyTime bump, same content
        val report = newEngine().run()

        assertEquals(1, report.unchanged)
        assertTrue(store.exists(ACCOUNT, "c1"))
        assertNotNull(db.contactMapDao().findByProtonId(ACCOUNT, "c1")?.lastKnownServerPayloadHash)
    }

    @Test
    fun server_contact_matching_a_placeholder_uid_collapses_into_the_pending_create() = runTest {
        // A create was pushed but its response was lost: the placeholder
        // mapping + outbox row are still around, and the next pull lists the
        // server contact carrying OUR client-generated UID (ADR 0007 §3).
        db.contactMapDao().upsert(placeholderMapping())
        db.outboxDao().insert(
            OutboxEntity(
                accountName = ACCOUNT,
                protonContactId = "local-7",
                opType = OutboxEntity.OpType.CREATE,
                payloadHash = "payload",
                createdAt = now,
            ),
        )
        decrypter = ContactDecrypter { request ->
            when (request) {
                is CardCryptoRequest.VerifyOnly -> CardCryptoOutcome(request.data, verified = true)
                else -> throw CardDecryptException("unexpected crypto op")
            }
        }
        listed = listOf(meta("srv-9"))
        dtos["srv-9"] = ContactDto(
            id = "srv-9",
            modifyTime = 1L,
            cards = listOf(
                ContactCardDto(
                    type = 2, // SIGNED — the only card type a UID is accepted from
                    data = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Alice\r\n" +
                        "UID:urn:uuid:dup\r\nEMAIL:alice@example.org\r\nEND:VCARD\r\n",
                    signature = "sig",
                ),
            ),
        )

        val report = newEngine().run()

        assertEquals(1, report.inserted)
        assertTrue(
            "no duplicate provider row",
            writer.applied.flatten().none {
                it is RawContactOpIntent.CreateContact && it.projected.protonContactId == "srv-9"
            },
        )
        assertEquals("provider row re-keyed in place", 7L, writer.existing["srv-9"])
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "local-7"))
        val mapping = db.contactMapDao().findByProtonId(ACCOUNT, "srv-9")
        assertEquals(7L, mapping?.androidRawContactId)
        assertEquals(ContactMapEntity.Status.CLEAN, mapping?.syncStatus)
        assertTrue(db.outboxDao().findByContact(ACCOUNT, "local-7").isEmpty())
        assertTrue(store.exists(ACCOUNT, "srv-9"))
    }

    @Test
    fun tombstone_sweep_removes_the_canonical_row_and_pending_outbox_rows() = runTest {
        addContact("c1", "Alice", "alice@example.org")
        newEngine().run()
        db.outboxDao().insert(
            OutboxEntity(
                accountName = ACCOUNT,
                protonContactId = "c1",
                opType = OutboxEntity.OpType.UPDATE,
                payloadHash = "payload",
                createdAt = now,
            ),
        )

        listed = emptyList()
        newEngine().run() // tombstoned
        now += TombstoneLifecycle.DEFAULT_GRACE_PERIOD_MS + 1
        newEngine().run() // swept

        assertTrue(!store.exists(ACCOUNT, "c1"))
        assertTrue(db.outboxDao().findByContact(ACCOUNT, "c1").isEmpty())
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "c1"))
    }

    private fun placeholderMapping() = ContactMapEntity(
        accountName = ACCOUNT,
        protonContactId = "local-7",
        protonUid = "urn:uuid:dup",
        androidRawContactId = 7L,
        modifyTime = 0L,
        contentHash = "local-content",
        photoHash = null,
        isVerified = true,
        syncStatus = ContactMapEntity.Status.PENDING_PUSH,
        lastError = null,
        lastSyncedAt = 1L,
        lastKnownServerPayloadHash = null,
    )

    private fun addContact(id: String, name: String, email: String) {
        listed = listed + meta(id)
        dtos[id] = ContactDto(id = id, modifyTime = 1L, cards = listOf(clearCard(vcard(name, email))))
    }

    private fun newEngine() = ContactsSyncEngine(
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

    private class FakeWriter : ContactsWriterGateway {
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
            return ApplyResult()
        }
    }

    private companion object {
        const val ACCOUNT = "default"

        fun meta(id: String, modifyTime: Long = 1L) = ContactMetadataDto(id = id, modifyTime = modifyTime)

        fun vcard(name: String, email: String): String =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:$name\r\nEMAIL:$email\r\nEND:VCARD\r\n"

        fun clearCard(data: String) = ContactCardDto(type = 0, data = data)
    }
}
