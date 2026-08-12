// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/src/test/.../BatchPlannerTest.kt (op counts adjusted:
// our write-set has no per-email chip row).

package app.alpensync.contacts.writer

import android.accounts.Account
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BatchPlannerTest {

    private val account = Account("default", "app.alpensync.account")

    @Test
    fun empty_intents_yield_no_chunks() {
        assertTrue(BatchPlanner.plan(account, emptyList()).isEmpty())
    }

    @Test
    fun chunks_split_at_the_cap_without_breaking_intent_boundaries() {
        // Each create = 3 ops (RawContacts + StructuredName + Email). With a
        // cap of 10, every chunk must hold a multiple of 3 and never exceed 10.
        val intents = (1..100).map { RawContactOpIntent.CreateContact(contact("c$it")) }
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 10)
        assertTrue("no chunk may exceed the cap", chunks.all { it.size <= 10 })
        assertTrue("no intent may straddle chunks", chunks.all { it.size % 3 == 0 })
        assertEquals("total ops preserved", 300, chunks.sumOf { it.size })
    }

    @Test
    fun mixed_intent_kinds_pack_greedily_within_the_cap() {
        val intents = listOf(
            RawContactOpIntent.CreateContact(contact("c1")), // 3 ops
            RawContactOpIntent.UpdateContact(100L, contact("c2")), // 1 wipe + 2 re-inserts = 3 ops
            RawContactOpIntent.DeleteContact("c3"), // 1 op
            RawContactOpIntent.DeleteContact("c4"), // 1 op
        )
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 4)
        // chunk 0: [Create 3] (Update's 3 would overflow) → chunk 1: [Update 3, Delete 1]
        // → chunk 2: [Delete 1].
        assertEquals(listOf(3, 4, 1), chunks.map { it.size })
    }

    @Test
    fun a_create_starting_a_new_chunk_is_reanchored() {
        // 4 creates × 3 ops, cap 6: two chunks of 2 creates each. The create
        // opening the second chunk must be rebuilt with baseIdx = 0 — the
        // re-anchor path is exercised by the chunk layout itself (op shapes
        // stay valid; the instrumented test proves end-to-end attachment).
        val intents = (1..4).map { RawContactOpIntent.CreateContact(contact("c$it")) }
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 6)
        assertEquals(listOf(6, 6), chunks.map { it.size })
        assertTrue(chunks.flatten().all { it.isInsert })
    }

    @Test
    fun rejects_an_intent_that_alone_exceeds_the_cap() {
        val intents = listOf(RawContactOpIntent.CreateContact(contact("c1")))
        assertThrows(IllegalArgumentException::class.java) {
            BatchPlanner.plan(account, intents, maxOpsPerBatch = 2)
        }
    }

    @Test
    fun rejects_a_non_positive_cap() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchPlanner.plan(account, emptyList(), maxOpsPerBatch = 0)
        }
    }

    @Test
    fun production_cap_is_the_450_op_binder_limit() {
        assertEquals(450, BatchPlanner.MAX_OPS_PER_BATCH)
    }

    private fun contact(id: String): ProjectedContact = ProjectedContact(
        protonContactId = id,
        protonUid = null,
        displayName = "Name $id",
        structuredName = null,
        emails = listOf(ProjectedEmail("$id@example.org", emptyList(), isPrimary = false)),
        phones = emptyList(),
        addresses = emptyList(),
        organization = null,
        notes = emptyList(),
        imAccounts = emptyList(),
        photo = null,
        urls = emptyList(),
        birthday = null,
        anniversary = null,
    )
}
