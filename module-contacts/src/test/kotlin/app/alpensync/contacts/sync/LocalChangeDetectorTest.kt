// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.vcard.CanonicalVCardEditor
import app.alpensync.contacts.vcard.CanonicalVCardText
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedPhoto
import app.alpensync.contacts.writer.ApplyResult
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.DirtyContact
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.OutboxEntity
import ezvcard.VCard
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
 * Dirty-detection semantics (ADR 0007 Section 2): the DIRTY/DELETED flag is
 * the gate, the reconciled content hash is the truth. Provider seams are
 * fakes; Room is real (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class LocalChangeDetectorTest {

    private lateinit var db: AlpenSyncDatabase
    private lateinit var store: CanonicalVCardStore
    private lateinit var writer: RecordingWriter
    private var dirty: List<DirtyContact> = emptyList()
    private val localRows = mutableMapOf<Long, ProjectedContact?>()
    private val cleared = mutableListOf<Long>()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlpenSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = CanonicalVCardStore(db.canonicalVCardDao(), { it }, { it }, {})
        writer = RecordingWriter()
    }

    @After
    fun tearDown() = db.close()

    @Test fun real_local_edit_enqueues_update_marks_pending_push_and_clears_the_flag() = runTest {
        val synced = seedSyncedContact()
        val edited = synced.copy(displayName = "Alice Renamed")
        localRows[RAW_ID] = edited
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))

        assertEquals(1, newDetector().scan())

        val row = outboxRows(CONTACT_ID).single()
        assertEquals(OutboxEntity.OpType.UPDATE, row.opType)
        assertEquals(LocalChangeDetector.combinedHash(edited), row.payloadHash)
        assertEquals(ContactMapEntity.Status.PENDING_PUSH, mapping(CONTACT_ID)?.syncStatus)
        assertEquals(listOf(RAW_ID), cleared)
    }

    @Test fun flagged_but_hash_equal_is_a_false_positive_nothing_enqueued() = runTest {
        val synced = seedSyncedContact()
        localRows[RAW_ID] = synced // identical content
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))

        assertEquals(0, newDetector().scan())
        assertTrue(outboxRows(CONTACT_ID).isEmpty())
        assertEquals(listOf(RAW_ID), cleared)
        assertEquals(ContactMapEntity.Status.CLEAN, mapping(CONTACT_ID)?.syncStatus)
    }

    @Test fun provider_echo_form_is_repaired_against_the_stored_baseline() = runTest {
        // The baseline email carries full type tokens; the provider read-back
        // collapses them to the writer's echo (no tokens, first row primary).
        val baseline = projection(emails = listOf(ProjectedEmail(EMAIL, listOf("home"), false)))
        seedSyncedContact(baseline)
        storeCanonical(baseline)
        localRows[RAW_ID] = projection(emails = listOf(ProjectedEmail(EMAIL, emptyList(), true)))
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))

        assertEquals(0, newDetector().scan())
        assertTrue(outboxRows(CONTACT_ID).isEmpty())
    }

    @Test fun re_edit_coalesces_into_the_single_pending_update_row() = runTest {
        val synced = seedSyncedContact()
        localRows[RAW_ID] = synced.copy(displayName = "First Edit")
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))
        newDetector().scan()
        val firstHash = outboxRows(CONTACT_ID).single().payloadHash

        localRows[RAW_ID] = synced.copy(displayName = "Second Edit")
        newDetector().scan()

        val rows = outboxRows(CONTACT_ID)
        assertEquals(1, rows.size)
        assertTrue(rows.single().payloadHash != firstHash)
    }

    @Test fun reverting_to_the_baseline_drops_the_pending_update() = runTest {
        val synced = seedSyncedContact()
        localRows[RAW_ID] = synced.copy(displayName = "Transient Edit")
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))
        newDetector().scan()
        assertEquals(1, outboxRows(CONTACT_ID).size)

        localRows[RAW_ID] = synced
        newDetector().scan()

        assertTrue(outboxRows(CONTACT_ID).isEmpty())
        assertEquals(ContactMapEntity.Status.CLEAN, mapping(CONTACT_ID)?.syncStatus)
    }

    @Test fun re_edit_during_delete_grace_cancels_the_delete_and_enqueues_update() = runTest {
        val synced = seedSyncedContact()
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = true))
        newDetector().scan()
        assertEquals(OutboxEntity.OpType.DELETE, outboxRows(CONTACT_ID).single().opType)

        localRows[RAW_ID] = synced.copy(displayName = "Undeleted")
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))
        newDetector().scan()

        val row = outboxRows(CONTACT_ID).single()
        assertEquals(OutboxEntity.OpType.UPDATE, row.opType)
    }

    @Test fun local_delete_enqueues_delete_and_cancels_a_pending_update() = runTest {
        val synced = seedSyncedContact()
        localRows[RAW_ID] = synced.copy(displayName = "Edited")
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))
        newDetector().scan()

        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = true))
        newDetector().scan()

        assertEquals(OutboxEntity.OpType.DELETE, outboxRows(CONTACT_ID).single().opType)
        assertEquals(ContactMapEntity.Status.PENDING_PUSH, mapping(CONTACT_ID)?.syncStatus)
    }

    @Test fun repeated_delete_scans_dedup_to_one_row() = runTest {
        seedSyncedContact()
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = true))
        newDetector().scan()
        newDetector().scan()
        assertEquals(1, outboxRows(CONTACT_ID).size)
    }

    @Test fun deleted_row_without_a_mapping_is_purged_never_enqueued() = runTest {
        dirty = listOf(DirtyContact(RAW_ID, "pc-ghost", isDirty = true, isDeleted = true))

        assertEquals(0, newDetector().scan())
        assertTrue(outboxRows("pc-ghost").isEmpty())
        val purge = writer.intents().filterIsInstance<RawContactOpIntent.DeleteContact>().single()
        assertEquals("pc-ghost", purge.sourceId)
        assertEquals(listOf(RAW_ID), cleared)
    }

    @Test fun deleting_a_never_synced_contact_cancels_its_pending_create() = runTest {
        localRows[RAW_ID] = projection("local-$RAW_ID")
        dirty = listOf(DirtyContact(RAW_ID, null, isDirty = true, isDeleted = false))
        newDetector().scan()
        assertEquals(1, outboxRows("local-$RAW_ID").size)

        dirty = listOf(DirtyContact(RAW_ID, null, isDirty = true, isDeleted = true))
        newDetector().scan()

        assertTrue(outboxRows("local-$RAW_ID").isEmpty())
        assertNull(mapping("local-$RAW_ID"))
    }

    @Test fun local_create_enqueues_create_with_placeholder_mapping_and_stable_uid() = runTest {
        localRows[RAW_ID] = projection("local-$RAW_ID")
        dirty = listOf(DirtyContact(RAW_ID, null, isDirty = true, isDeleted = false))

        assertEquals(1, newDetector().scan())

        assertEquals(OutboxEntity.OpType.CREATE, outboxRows("local-$RAW_ID").single().opType)
        val placeholder = mapping("local-$RAW_ID")
        assertNotNull(placeholder)
        assertTrue(placeholder?.protonUid?.startsWith("urn:uuid:") ?: false)
        assertEquals(ContactMapEntity.Status.PENDING_PUSH, placeholder?.syncStatus)

        // A re-edit scan neither duplicates the row nor changes the UID.
        newDetector().scan()
        assertEquals(1, outboxRows("local-$RAW_ID").size)
        assertEquals(placeholder?.protonUid, mapping("local-$RAW_ID")?.protonUid)
    }

    @Test fun an_unreadable_row_keeps_its_dirty_flag_and_enqueues_nothing() = runTest {
        seedSyncedContact()
        // localRows has no entry → the provider read fails (null)
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))

        assertEquals(0, newDetector().scan())
        assertTrue(outboxRows(CONTACT_ID).isEmpty())
        assertTrue(cleared.isEmpty())
    }

    @Test fun a_photo_only_change_enqueues_an_update() = runTest {
        val synced = seedSyncedContact()
        localRows[RAW_ID] = synced.copy(photo = ProjectedPhoto(byteArrayOf(1, 2, 3), mimeType = null))
        dirty = listOf(DirtyContact(RAW_ID, CONTACT_ID, isDirty = true, isDeleted = false))

        assertEquals(1, newDetector().scan())
        assertEquals(OutboxEntity.OpType.UPDATE, outboxRows(CONTACT_ID).single().opType)
    }

    private fun newDetector() = LocalChangeDetector(
        accountName = ACCOUNT,
        stores = ContactsSyncStore(db, store),
        writer = writer,
        readDirty = { dirty },
        readLocal = { rawId, _ -> localRows[rawId] },
        clearDirty = { cleared += it },
        clock = { now },
    )

    /** A contact synced before: mapping row with hashes computed off [content]. */
    private suspend fun seedSyncedContact(
        content: ProjectedContact = projection(),
    ): ProjectedContact {
        db.contactMapDao().upsert(
            ContactMapEntity(
                accountName = ACCOUNT,
                protonContactId = CONTACT_ID,
                protonUid = "urn:uuid:$CONTACT_ID",
                androidRawContactId = RAW_ID,
                modifyTime = 1L,
                contentHash = ContactHasher.contentHash(content),
                photoHash = ContactHasher.photoHash(content),
                isVerified = true,
                syncStatus = ContactMapEntity.Status.CLEAN,
                lastError = null,
                lastSyncedAt = 1L,
                lastKnownServerPayloadHash = "hash-$CONTACT_ID",
            ),
        )
        return content
    }

    /** Writes the canonical vCard the projection [content] projects from — the detector's reconcile baseline. */
    private suspend fun storeCanonical(content: ProjectedContact) {
        val canonical = CanonicalVCardEditor.applyEdits(
            VCard(),
            content,
            CanonicalVCardEditor.PhotoUpdate.REPLACE_FROM_PROJECTION,
        )
        store.write(ACCOUNT, CONTACT_ID, CanonicalVCardText.write(canonical))
    }

    private suspend fun mapping(id: String) = db.contactMapDao().findByProtonId(ACCOUNT, id)

    private suspend fun outboxRows(id: String) = db.outboxDao().findByContact(ACCOUNT, id)

    private class RecordingWriter : ContactsWriterGateway {
        val applied = mutableListOf<List<RawContactOpIntent>>()
        fun intents() = applied.flatten()
        override fun readExistingRawIds(): Map<String, Long> = emptyMap()
        override fun apply(intents: List<RawContactOpIntent>): ApplyResult {
            applied += intents
            return ApplyResult()
        }
    }

    private companion object {
        const val ACCOUNT = "default"
        const val CONTACT_ID = "pc-1"
        const val RAW_ID = 7L
        const val EMAIL = "alice@example.org"

        fun projection(
            id: String = CONTACT_ID,
            displayName: String = "Alice",
            emails: List<ProjectedEmail> = listOf(ProjectedEmail(EMAIL, emptyList(), false)),
        ): ProjectedContact = LocalChangeDetector.emptyProjection(id).copy(displayName = displayName, emails = emails)
    }
}
