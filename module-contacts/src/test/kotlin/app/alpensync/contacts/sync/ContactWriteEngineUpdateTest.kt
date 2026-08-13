// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.ACCOUNT
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.BASE_VCARD
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.canonicalOf
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.httpError
import app.alpensync.contacts.sync.WriteEngineFixture.Companion.vcard
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.contacts.vcard.ContactProjection
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.UpdateContactResponse
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ConflictCopyEntity
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.OutboxEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UPDATE drain semantics: the ADR 0006 Option-B merge on the stored canonical
 * base, conflict-copy preservation, and the retry/quarantine classification
 * (ADR 0007 Section 4). Robolectric = real SQLite; the API and provider are
 * fakes; SIGNED cards stay plaintext so payload assertions read them.
 */
@RunWith(RobolectricTestRunner::class)
class ContactWriteEngineUpdateTest {

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

    @Test fun update_without_divergence_pushes_local_edit_and_advances_bookkeeping() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base", "+9-999")

        val report = fixture.newEngine().push()

        assertEquals(1, report.updated)
        assertEquals(0, report.conflicts)
        val payload = fixture.api.updateRequests.single().cards
        assertTrue(payload[0].data.contains("FN:Alice Base"))
        assertTrue(payload[1].data.contains("+9-999"))
        val mapping = db.contactMapDao().findByProtonId(ACCOUNT, "pc-1")
        assertEquals(ContactMapEntity.Status.CLEAN, mapping?.syncStatus)
        assertEquals(43L, mapping?.modifyTime)
        assertNotNull(mapping?.lastKnownServerPayloadHash)
        assertNull(fixture.outboxRow("pc-1"))
        val stored = fixture.store.read(ACCOUNT, "pc-1")
        assertTrue(stored != null && stored.contains("+9-999"))
        // No divergence → no provider rewrite (the phone already shows the edit).
        assertTrue(fixture.writer.intents().isEmpty())
        assertTrue(db.conflictCopyDao().listForContact(ACCOUNT, "pc-1").isEmpty())
    }

    @Test fun update_provider_apply_failure_leaves_the_pre_push_canonical_base() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD.replace("Alice Base", "Alice Server"))
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base", "+9-999")
        fixture.writer.failApply = { throw IOException("provider apply failed") }

        val report = fixture.newEngine().push()

        assertEquals(1, report.retried)
        val stored = checkNotNull(fixture.store.read(ACCOUNT, "pc-1"))
        assertTrue("retry must still see the pre-push ancestor", stored.contains("FN:Alice Base"))
        assertFalse(stored.contains("FN:Alice Server"))
        assertNotNull(fixture.outboxRow("pc-1"))
        assertEquals(ContactMapEntity.Status.PENDING_PUSH, db.contactMapDao().findByProtonId(ACCOUNT, "pc-1")?.syncStatus)
    }

    @Test fun update_with_disjoint_server_edit_merges_and_applies_server_state_to_provider() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD.replace("Alice Base", "Alice Server"))
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base", "+9-999")

        val report = fixture.newEngine().push()

        assertEquals(1, report.updated)
        assertEquals(0, report.conflicts)
        val payload = fixture.api.updateRequests.single().cards
        assertTrue(payload[0].data.contains("FN:Alice Server"))
        assertTrue(payload[1].data.contains("+9-999"))
        // The merged state (server's FN) lands on the phone.
        val providerUpdate = fixture.writer.intents().filterIsInstance<RawContactOpIntent.UpdateContact>().single()
        assertEquals("Alice Server", providerUpdate.projected.displayName)
        assertTrue(db.conflictCopyDao().listForContact(ACCOUNT, "pc-1").isEmpty())
    }

    @Test fun update_with_same_field_both_sides_server_wins_and_loser_is_preserved() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD.replace("Alice Base", "Alice Server"))
        fixture.localRows[7L] = localProjection("pc-1", "Alice Local")

        val report = fixture.newEngine().push()

        assertEquals(1, report.updated)
        assertEquals(1, report.conflicts)
        val payload = fixture.api.updateRequests.single().cards
        assertTrue(payload[0].data.contains("FN:Alice Server"))

        val copy = db.conflictCopyDao().listForContact(ACCOUNT, "pc-1").single()
        assertEquals(ConflictCopyEntity.LosingSide.LOCAL, copy.losingSide)
        assertEquals(ConflictCopyEntity.Resolution.SERVER_WON, copy.resolution)
        // The identity-wrap store leaves the losing vCard readable in-test.
        val losingPayload = copy.payloadEnc?.toString(Charsets.UTF_8)
        assertTrue(losingPayload != null && losingPayload.contains("FN:Alice Local"))
        assertTrue(copy.fieldConflictsJson != null && checkNotNull(copy.fieldConflictsJson).contains("\"f\":\"fn\""))
        // The phone converges to the server value.
        val providerUpdate = fixture.writer.intents().filterIsInstance<RawContactOpIntent.UpdateContact>().single()
        assertEquals("Alice Server", providerUpdate.projected.displayName)
    }

    @Test fun update_without_stored_base_rebases_on_the_server_state() = runTest {
        // No canonical store row, null payload hash (M2-era mapping): the
        // re-fetched server state becomes the base (ADR 0007 Section 5(ii)).
        val baseText = CanonicalVCardText.write(vcard(BASE_VCARD))
        fixture.seedMapping(
            "pc-1",
            rawId = 7L,
            status = ContactMapEntity.Status.PENDING_PUSH,
            lastKnownHash = null,
            contentHash = ContactHasher.contentHash(baselineProjection()),
        )
        fixture.seedOutbox("pc-1", OutboxEntity.OpType.UPDATE)
        fixture.api.fetchCanonicalHandler = { canonicalOf("pc-1", baseText) }
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base", "+9-999")

        val report = fixture.newEngine().push()

        assertEquals(1, report.updated)
        assertEquals(0, report.conflicts)
        assertTrue(fixture.api.updateRequests.single().cards[1].data.contains("+9-999"))
        assertTrue(fixture.store.exists(ACCOUNT, "pc-1"))
    }

    @Test fun update_with_server_rejection_quarantines() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base")
        fixture.api.updateHandler = { _, _ -> UpdateContactResponse(code = 2501, contact = null) }

        val report = fixture.newEngine().push()

        assertEquals(1, report.quarantined)
        assertEquals("update_rejected", fixture.outboxRow("pc-1")?.lastError)
    }

    @Test fun update_without_mapping_quarantines() = runTest {
        fixture.seedOutbox("pc-1", OutboxEntity.OpType.UPDATE)
        val report = fixture.newEngine().push()
        assertEquals(1, report.quarantined)
        assertEquals("update_mapping_missing", fixture.outboxRow("pc-1")?.lastError)
    }

    @Test fun update_with_undecryptable_server_cards_quarantines_never_pushes_blind() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base")
        fixture.api.fetchCanonicalHandler = { null }

        val report = fixture.newEngine().push()

        assertEquals(1, report.quarantined)
        assertEquals("server_cards_undecryptable", fixture.outboxRow("pc-1")?.lastError)
        assertTrue(fixture.api.updateRequests.isEmpty())
    }

    @Test fun update_with_vanished_local_row_quarantines() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)

        val report = fixture.newEngine().push()

        assertEquals(1, report.quarantined)
        assertEquals("update_local_missing", fixture.outboxRow("pc-1")?.lastError)
    }

    @Test fun transport_failure_retries_with_quadratic_backoff() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base")
        fixture.api.updateHandler = { _, _ -> throw IOException("simulated transport failure") }

        val report = fixture.newEngine().push()

        assertEquals(1, report.retried)
        val row = fixture.outboxRow("pc-1")
        assertEquals(1, row?.attempts)
        assertEquals(fixture.now + 30_000L, row?.nextAttemptAt)
        assertFalse(row?.quarantined ?: true)
    }

    @Test fun http_429_retries_and_http_400_quarantines() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base")
        fixture.api.updateHandler = { _, _ -> throw httpError(429) }
        assertEquals(1, fixture.newEngine().push().retried)
        assertEquals(1, fixture.outboxRow("pc-1")?.attempts)

        // The retry is backoff-gated; advancing past it lets the next drain try.
        fixture.now += 31_000L
        fixture.api.updateHandler = { _, _ -> throw httpError(400) }
        assertEquals(1, fixture.newEngine().push().quarantined)
        assertEquals(true, fixture.outboxRow("pc-1")?.quarantined)
    }

    @Test fun human_verification_propagates_and_leaves_the_entry_untouched() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base")
        fixture.api.fetchCanonicalHandler = { throw HumanVerificationRequiredException() }

        try {
            fixture.newEngine().push()
            fail("expected HumanVerificationRequiredException to propagate")
        } catch (expected: HumanVerificationRequiredException) {
            // the M1 HV flow fires above the engine
        }
        val row = fixture.outboxRow("pc-1")
        assertEquals(0, row?.attempts)
        assertFalse(row?.quarantined ?: true)
    }

    @Test fun cancellation_propagates() = runTest {
        seedPendingUpdate(theirs = BASE_VCARD)
        fixture.localRows[7L] = localProjection("pc-1", "Alice Base")
        fixture.api.fetchCanonicalHandler = { throw CancellationException("simulated cancel") }

        try {
            fixture.newEngine().push()
            fail("expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            // cancellation is never swallowed
        }
    }

    /**
     * A pending UPDATE for pc-1: stored canonical base + payload hash both
     * from BASE_VCARD (the last-synced state), while [theirs] is what the
     * server returns NOW — passing a modified card sets up divergence.
     */
    private suspend fun seedPendingUpdate(theirs: String) {
        val baseText = CanonicalVCardText.write(vcard(BASE_VCARD))
        fixture.store.write(ACCOUNT, "pc-1", baseText)
        fixture.seedMapping(
            "pc-1",
            rawId = 7L,
            status = ContactMapEntity.Status.PENDING_PUSH,
            lastKnownHash = CanonicalVCardText.payloadHash(baseText),
            contentHash = ContactHasher.contentHash(baselineProjection()),
        )
        fixture.seedOutbox("pc-1", OutboxEntity.OpType.UPDATE)
        val theirsText = CanonicalVCardText.write(vcard(theirs))
        fixture.api.fetchCanonicalHandler = { canonicalOf("pc-1", theirsText) }
    }

    private fun baselineProjection() =
        ContactProjection.project(CanonicalContact.ofVCard("pc-1", vcard(BASE_VCARD)))
}
