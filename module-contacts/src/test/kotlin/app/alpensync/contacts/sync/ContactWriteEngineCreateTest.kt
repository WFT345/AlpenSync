// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.ContactDto
import app.alpensync.core.api.dto.CreateContactResponseBody
import app.alpensync.core.api.dto.CreateContactResponseItem
import app.alpensync.core.api.dto.CreateContactsResponse
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
 * CREATE drain semantics (ADR 0007 Sections 3-4): server-ID re-key, stable
 * UID across retries, sub-response handling, and the vanished-row abandon.
 * Robolectric = real SQLite; the API and provider are fakes.
 */
@RunWith(RobolectricTestRunner::class)
class ContactWriteEngineCreateTest {

    private lateinit var db: AlpenSyncDatabase
    private lateinit var fixture: WriteEngineFixture

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        fixture = WriteEngineFixture(db)
    }

    @After
    fun tearDown() = db.close()

    @Test fun create_success_rekeys_mapping_stamps_source_id_and_persists_canonical() = runTest {
        seedPendingCreate()
        val report = fixture.newEngine().push()

        assertEquals(1, report.created)
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "local-7"))
        val mapping = db.contactMapDao().findByProtonId(ACCOUNT, "srv-created")
        assertNotNull(mapping)
        assertEquals(ContactMapEntity.Status.CLEAN, mapping?.syncStatus)
        assertEquals("urn:uuid:stable-1", mapping?.protonUid)
        assertEquals(7L, mapping?.androidRawContactId)
        assertTrue(fixture.writer.intents().single() is RawContactOpIntent.SetSourceId)
        assertTrue(fixture.store.exists(ACCOUNT, "srv-created"))
        assertNull(fixture.outboxRow("local-7"))
        // The pushed SIGNED card carries the UID captured at enqueue time.
        val signedCard = fixture.api.createRequests.single().contacts.single().cards[0].data
        assertTrue(signedCard.contains("UID:urn:uuid:stable-1"))
    }

    @Test fun create_transport_failure_quarantines_instead_of_retrying_the_post() = runTest {
        seedPendingCreate()
        fixture.api.createHandler = { throw IOException("simulated transport failure") }

        val report = fixture.newEngine().push()

        assertEquals(1, report.quarantined)
        assertEquals(1, fixture.api.createRequests.size)
        val row = fixture.outboxRow("local-7")
        assertEquals(true, row?.quarantined)
        assertEquals("create_maybe_landed", row?.lastError)
        assertNotNull(db.contactMapDao().findByProtonId(ACCOUNT, "local-7"))
    }

    @Test fun create_subresponse_rejection_quarantines_the_entry() = runTest {
        seedPendingCreate()
        fixture.api.createHandler = {
            CreateContactsResponse(
                1000,
                listOf(CreateContactResponseItem(0, CreateContactResponseBody(2501, null))),
            )
        }

        val report = fixture.newEngine().push()

        assertEquals(1, report.quarantined)
        val row = fixture.outboxRow("local-7")
        assertEquals(true, row?.quarantined)
        assertEquals("create_rejected", row?.lastError)
        assertNotNull(db.contactMapDao().findByProtonId(ACCOUNT, "local-7"))
    }

    @Test fun create_response_without_item_quarantines_instead_of_retrying() = runTest {
        seedPendingCreate()
        fixture.api.createHandler = { CreateContactsResponse(code = 1000, responses = emptyList()) }

        val report = fixture.newEngine().push()

        assertEquals(1, report.quarantined)
        val row = fixture.outboxRow("local-7")
        assertEquals(true, row?.quarantined)
        assertEquals("create_maybe_landed", row?.lastError)
    }

    @Test fun create_without_placeholder_mapping_quarantines() = runTest {
        fixture.seedOutbox("local-7", OutboxEntity.OpType.CREATE)

        val report = fixture.newEngine().push()

        assertEquals(1, report.quarantined)
        assertEquals("create_mapping_missing", fixture.outboxRow("local-7")?.lastError)
    }

    @Test fun create_whose_provider_row_vanished_is_abandoned_without_a_call() = runTest {
        seedPendingCreate()
        fixture.localRows.remove(7L)

        val report = fixture.newEngine().push()

        assertEquals(WriteReport(), report)
        assertTrue(fixture.api.calls.isEmpty())
        assertNull(fixture.outboxRow("local-7"))
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "local-7"))
    }

    private suspend fun seedPendingCreate() {
        fixture.seedMapping(
            "local-7",
            rawId = 7L,
            uid = "urn:uuid:stable-1",
            status = ContactMapEntity.Status.PENDING_PUSH,
        )
        fixture.seedOutbox("local-7", OutboxEntity.OpType.CREATE)
        fixture.localRows[7L] = localProjection("local-7", "Alice Local")
    }

    private fun createdDto() = ContactDto(
        id = "srv-created",
        uid = "urn:uuid:stable-1",
        modifyTime = 42L,
    )

    private companion object {
        const val ACCOUNT = WriteEngineFixture.ACCOUNT
    }
}
