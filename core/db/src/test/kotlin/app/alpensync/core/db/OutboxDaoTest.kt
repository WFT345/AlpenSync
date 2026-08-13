// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.db.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Outbox semantics (ADR 0007 Section 4): FIFO by created_at over
 * non-quarantined rows whose backoff expired; quarantine side-lines until
 * requeue; account isolation everywhere. Robolectric = real SQLite.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxDaoTest {

    private lateinit var db: AlpenSyncDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test fun listReadyIsFifoAndRespectsBackoffAndQuarantine() = runTest {
        db.outboxDao().insert(row("acct", "pc-old", createdAt = 1L))
        db.outboxDao().insert(row("acct", "pc-new", createdAt = 2L))
        db.outboxDao().insert(row("acct", "pc-later", createdAt = 3L, nextAttemptAt = 1_000L))
        val quarantinedId = db.outboxDao().insert(row("acct", "pc-quar", createdAt = 4L))
        db.outboxDao().quarantine(quarantinedId, "permanent 4xx")

        val ready = db.outboxDao().listReady("acct", now = 500L)

        assertEquals(listOf("pc-old", "pc-new"), ready.map { it.protonContactId })
    }

    @Test fun recordFailureAdvancesAttemptsAndBackoff() = runTest {
        val id = db.outboxDao().insert(row("acct", "pc-1"))
        db.outboxDao().recordFailure(id, attempts = 2, error = "HTTP 500", nextAt = 9_000L)

        val row = db.outboxDao().findByContact("acct", "pc-1").single()
        assertEquals(2, row.attempts)
        assertEquals("HTTP 500", row.lastError)
        assertEquals(9_000L, row.nextAttemptAt)
        assertTrue(db.outboxDao().listReady("acct", now = 8_999L).isEmpty())
    }

    @Test fun requeueClearsQuarantineAndBackoffState() = runTest {
        val id = db.outboxDao().insert(row("acct", "pc-1"))
        db.outboxDao().quarantine(id, "HTTP 400")
        assertEquals(1, db.outboxDao().countQuarantined("acct"))

        db.outboxDao().requeue(id)

        val row = db.outboxDao().findByContact("acct", "pc-1").single()
        assertEquals(0, row.attempts)
        assertNull(row.lastError)
        assertEquals(0L, row.nextAttemptAt)
        assertEquals(0, db.outboxDao().countQuarantined("acct"))
        assertEquals(1, db.outboxDao().listReady("acct", now = 0L).size)
    }

    @Test fun listPendingDeletesFeedsTheGraceSettingsSurface() = runTest {
        db.outboxDao().insert(row("acct", "pc-del", op = OutboxEntity.OpType.DELETE))
        db.outboxDao().insert(row("acct", "pc-upd", op = OutboxEntity.OpType.UPDATE))
        val quarId = db.outboxDao().insert(row("acct", "pc-del-quar", op = OutboxEntity.OpType.DELETE))
        db.outboxDao().quarantine(quarId, "x")

        assertEquals(listOf("pc-del"), db.outboxDao().listPendingDeletes("acct").map { it.protonContactId })
    }

    @Test fun deleteByContactAndAccountIsolation() = runTest {
        db.outboxDao().insert(row("acct-a", "pc-1"))
        db.outboxDao().insert(row("acct-b", "pc-1"))

        db.outboxDao().deleteByContact("acct-a", "pc-1")

        assertTrue(db.outboxDao().findByContact("acct-a", "pc-1").isEmpty())
        assertEquals(1, db.outboxDao().countPending("acct-b"))
        db.outboxDao().deleteAllForAccount("acct-b")
        assertEquals(0, db.outboxDao().countPending("acct-b"))
    }

    private fun row(
        account: String,
        contactId: String,
        op: Int = OutboxEntity.OpType.UPDATE,
        createdAt: Long = 1L,
        nextAttemptAt: Long = 0L,
    ) = OutboxEntity(
        accountName = account,
        protonContactId = contactId,
        opType = op,
        payloadHash = "ph-$contactId",
        createdAt = createdAt,
        nextAttemptAt = nextAttemptAt,
    )
}
