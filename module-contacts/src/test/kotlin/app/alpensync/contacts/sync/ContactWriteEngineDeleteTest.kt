// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.ACCOUNT
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.BASE_VCARD
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.canonicalOf
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.vcard
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.BulkDeleteResponse
import app.alpensync.core.api.dto.DeleteResponseBody
import app.alpensync.core.api.dto.DeleteResponseItem
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.OutboxEntity
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
 * DELETE drain semantics (ADR 0007 Section 4): the 1-hour grace, per-ID
 * sub-response containment (one failure never loses the siblings), and the
 * provider-row purge; plus the drain's global ordering guarantee.
 */
@RunWith(RobolectricTestRunner::class)
class ContactWriteEngineDeleteTest {

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

    @Test fun delete_inside_the_grace_window_is_skipped_not_failed() = runTest {
        seedMapping("pc-1")
        fixture.seedOutbox("pc-1", OutboxEntity.OpType.DELETE, createdAt = fixture.now - 1_000L)

        val report = fixture.newEngine().push()

        assertEquals(1, report.skippedGrace)
        assertTrue(fixture.api.deleteRequests.isEmpty())
        assertNotNull(fixture.outboxRow("pc-1"))
        assertNotNull(db.contactMapDao().findByProtonId(ACCOUNT, "pc-1"))
    }

    @Test fun delete_after_grace_cleans_mapping_store_outbox_and_provider_row() = runTest {
        seedMapping("pc-1")
        fixture.store.write(ACCOUNT, "pc-1", "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:A\r\nEND:VCARD\r\n")
        fixture.seedOutbox("pc-1", OutboxEntity.OpType.DELETE, createdAt = fixture.now - OutboxEntity.GRACE_PERIOD_MS - 1)

        val report = fixture.newEngine().push()

        assertEquals(1, report.deleted)
        assertEquals(listOf(listOf("pc-1")), fixture.api.deleteRequests.map { it.ids })
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "pc-1"))
        assertNull(fixture.outboxRow("pc-1"))
        assertTrue(!fixture.store.exists(ACCOUNT, "pc-1"))
        val purge = fixture.writer.intents().filterIsInstance<RawContactOpIntent.DeleteContact>().single()
        assertEquals("pc-1", purge.sourceId)
    }

    @Test fun delete_subresponse_failure_keeps_only_that_entry_queued() = runTest {
        seedMapping("pc-a")
        seedMapping("pc-b")
        val past = fixture.now - OutboxEntity.GRACE_PERIOD_MS - 1
        fixture.seedOutbox("pc-a", OutboxEntity.OpType.DELETE, createdAt = past)
        fixture.seedOutbox("pc-b", OutboxEntity.OpType.DELETE, createdAt = past)
        fixture.api.deleteHandler = { request ->
            val id = request.ids.single()
            val code = if (id == "pc-a") 2501 else 1000
            BulkDeleteResponse(1000, listOf(DeleteResponseItem(id, DeleteResponseBody(code))))
        }

        val report = fixture.newEngine().push()

        assertEquals(1, report.deleted)
        assertEquals(1, report.retried)
        // The failing ID survives with attempt metadata; the sibling is done.
        val failed = fixture.outboxRow("pc-a")
        assertNotNull(failed)
        assertEquals(1, failed?.attempts)
        assertEquals("delete_subcode", failed?.lastError)
        assertNotNull(db.contactMapDao().findByProtonId(ACCOUNT, "pc-a"))
        assertNull(fixture.outboxRow("pc-b"))
        assertNull(db.contactMapDao().findByProtonId(ACCOUNT, "pc-b"))
    }

    @Test fun delete_for_an_already_unmapped_contact_is_fulfilled_without_a_call() = runTest {
        fixture.seedOutbox("pc-gone", OutboxEntity.OpType.DELETE, createdAt = fixture.now - OutboxEntity.GRACE_PERIOD_MS - 1)

        val report = fixture.newEngine().push()

        assertEquals(1, report.deleted)
        assertTrue(fixture.api.deleteRequests.isEmpty())
        assertNull(fixture.outboxRow("pc-gone"))
    }

    @Test fun drain_orders_creates_before_updates_before_deletes() = runTest {
        val past = fixture.now - OutboxEntity.GRACE_PERIOD_MS - 1
        // Seeded in reverse order on purpose; created_at interleaves too.
        seedMapping("pc-del")
        fixture.seedOutbox("pc-del", OutboxEntity.OpType.DELETE, createdAt = past)
        seedPendingUpdate()
        fixture.seedMapping("local-9", rawId = 9L, uid = "urn:uuid:nine", status = ContactMapEntity.Status.PENDING_PUSH)
        fixture.seedOutbox("local-9", OutboxEntity.OpType.CREATE, createdAt = past)
        fixture.localRows[9L] = localProjection("local-9", "New Nine")

        val report = fixture.newEngine().push()

        // "fetch" is the update's pre-push merge fetch, not a write call.
        assertEquals(listOf("create", "update", "delete"), fixture.api.calls.filter { it != "fetch" })
        assertEquals(1, report.created)
        assertEquals(1, report.updated)
        assertEquals(1, report.deleted)
    }

    /** A minimal valid pending UPDATE for the ordering test. */
    private suspend fun seedPendingUpdate() {
        val baseText = CanonicalVCardText.write(vcard(BASE_VCARD))
        fixture.store.write(ACCOUNT, "pc-1", baseText)
        fixture.seedMapping(
            "pc-1",
            rawId = 7L,
            status = ContactMapEntity.Status.PENDING_PUSH,
            lastKnownHash = CanonicalVCardText.payloadHash(baseText),
        )
        fixture.seedOutbox("pc-1", OutboxEntity.OpType.UPDATE, createdAt = fixture.now)
        fixture.api.fetchCanonicalHandler = { canonicalOf("pc-1", baseText) }
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base")
    }

    private suspend fun seedMapping(id: String) = fixture.seedMapping(id, rawId = 1_000L)
}
