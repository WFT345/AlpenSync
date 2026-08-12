// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CardType
import app.alpensync.contacts.vcard.ContactProjection
import app.alpensync.contacts.vcard.DecryptedCard
import app.alpensync.contacts.vcard.VCardMerger
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.TombstoneEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diff classification: new / changed (content vs photo-only) / unchanged /
 * deleted, plus the tombstone interactions (restore-on-reappear,
 * still-pending) and the accepted name-only-contact gap. All fixtures are
 * synthetic (Rule 1); hashes are REAL — produced by ContactHasher through
 * the M2b pipeline, never hand-written.
 */
class ContactDifferTest {

    @Test fun unknownRemoteContactIsNew() {
        val diff = ContactDiffer.diff(listOf(contact("pc-1")), emptyList(), emptyList())
        assertEquals(listOf("pc-1"), diff.newContacts.map { it.projected.protonContactId })
        assertTrue(diff.changedContacts.isEmpty())
        assertTrue(diff.deletedContacts.isEmpty())
    }

    @Test fun hashMatchIsUnchanged() {
        val canonical = contact("pc-1")
        val diff = ContactDiffer.diff(listOf(canonical), listOf(mappingOf(canonical)), emptyList())
        assertEquals(listOf("pc-1"), diff.unchangedContacts.map { it.mapping.protonContactId })
        assertTrue(diff.isNoOp())
    }

    @Test fun contentChangeIsClassifiedContent() {
        val before = contact("pc-1", "EMAIL:alice@example.com\r\n")
        val after = contact("pc-1", "EMAIL:alice@example.org\r\n")
        val diff = ContactDiffer.diff(listOf(after), listOf(mappingOf(before)), emptyList())
        val changed = diff.changedContacts.single()
        assertEquals(ChangeReason.CONTENT, changed.reason)
        assertEquals("pc-1", changed.mapping.protonContactId)
    }

    @Test fun photoOnlyChangeIsClassifiedPhotoOnly() {
        val before = contact("pc-1", "TEL:1\r\n")
        val after = contact("pc-1", "TEL:1\r\nPHOTO:data:image/gif;base64,R0lGODlhAQABAAAAACw=\r\n")
        val diff = ContactDiffer.diff(listOf(after), listOf(mappingOf(before)), emptyList())
        val changed = diff.changedContacts.single()
        assertEquals(ChangeReason.PHOTO_ONLY, changed.reason)
        assertEquals(ContactHasher.contentHash(after.let(::projectionOf)), changed.contentHash)
    }

    @Test fun mappedContactMissingFromTheRemoteSetIsDeleted() {
        val gone = contact("pc-gone", "EMAIL:gone@example.com\r\n")
        val stays = contact("pc-stays", "EMAIL:stays@example.com\r\n")
        val diff = ContactDiffer.diff(
            listOf(stays),
            listOf(mappingOf(gone), mappingOf(stays)),
            emptyList(),
        )
        assertEquals(listOf("pc-gone"), diff.deletedContacts.map { it.mapping.protonContactId })
        assertEquals(1, diff.pendingDeletionCount)
    }

    @Test fun reappearingContactCancelsItsTombstone() {
        val canonical = contact("pc-1")
        val tombstone = tombstoneOf(canonical)
        val diff = ContactDiffer.diff(listOf(canonical), listOf(mappingOf(canonical)), listOf(tombstone))
        assertEquals(listOf(tombstone), diff.restored)
        assertTrue(diff.stillTombstoned.isEmpty())
        // ... and it is still classified normally on top of the restore.
        assertEquals(listOf("pc-1"), diff.unchangedContacts.map { it.mapping.protonContactId })
    }

    @Test fun vanishedContactWithLiveTombstoneIsStillPendingNotReDeleted() {
        val gone = contact("pc-gone", "EMAIL:gone@example.com\r\n")
        val tombstone = tombstoneOf(gone)
        val diff = ContactDiffer.diff(emptyList(), listOf(mappingOf(gone)), listOf(tombstone))
        assertTrue(diff.deletedContacts.isEmpty())
        assertEquals(listOf(tombstone), diff.stillTombstoned)
        assertEquals(1, diff.pendingDeletionCount)
    }

    @Test fun nameOnlyContactIsSkippedAndReportedNotWritten() {
        val nameOnly = contact("pc-name-only", "FN:Bob\r\n")
        val diff = ContactDiffer.diff(listOf(nameOnly), emptyList(), emptyList())
        assertEquals(listOf("pc-name-only"), diff.skippedNotSyncable.map { it.protonContactId })
        assertTrue(diff.newContacts.isEmpty())
    }

    @Test fun previouslySyncedContactThatBecameNameOnlyIsDeleted() {
        val syncable = contact("pc-1", "FN:Bob\r\nEMAIL:bob@example.com\r\n")
        val nameOnlyNow = contact("pc-1", "FN:Bob\r\n")
        val diff = ContactDiffer.diff(listOf(nameOnlyNow), listOf(mappingOf(syncable)), emptyList())
        assertEquals(listOf("pc-1"), diff.deletedContacts.map { it.mapping.protonContactId })
        assertEquals(listOf("pc-1"), diff.skippedNotSyncable.map { it.protonContactId })
    }

    @Test fun emptyInputsProduceANoOpDiff() {
        assertTrue(ContactDiffer.diff(emptyList(), emptyList(), emptyList()).isNoOp())
    }

    private fun contact(protonId: String, body: String = "EMAIL:a@example.com\r\n"): CanonicalContact =
        VCardMerger.merge(
            protonId,
            listOf(
                DecryptedCard(
                    CardType.SIGNED,
                    "BEGIN:VCARD\r\nVERSION:4.0\r\n${body}END:VCARD\r\n",
                    verified = true,
                ),
            ),
        )

    private fun projectionOf(canonical: CanonicalContact) = ContactProjection.project(canonical)

    private fun mappingOf(canonical: CanonicalContact, account: String = "acct"): ContactMapEntity {
        val projected = projectionOf(canonical)
        return ContactMapEntity(
            accountName = account,
            protonContactId = canonical.protonContactId,
            protonUid = canonical.protonUid,
            androidRawContactId = canonical.protonContactId.hashCode().toLong(),
            modifyTime = 1000L,
            contentHash = ContactHasher.contentHash(projected),
            photoHash = ContactHasher.photoHash(projected),
            isVerified = true,
            syncStatus = ContactMapEntity.Status.CLEAN,
            lastError = null,
            lastSyncedAt = 2000L,
        )
    }

    private fun tombstoneOf(canonical: CanonicalContact, account: String = "acct"): TombstoneEntity =
        TombstoneEntity(
            accountName = account,
            protonContactId = canonical.protonContactId,
            androidRawContactId = canonical.protonContactId.hashCode().toLong(),
            deletedAt = 3000L,
            expiresAt = 4000L,
        )
}
