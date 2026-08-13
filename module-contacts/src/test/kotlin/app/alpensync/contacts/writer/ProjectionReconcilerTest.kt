// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.writer

import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedName
import app.alpensync.contacts.vcard.ProjectedPhone
import app.alpensync.contacts.vcard.ProjectedPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reconciler's fidelity contract (ADR 0007 Section 2): an untouched
 * contact's provider echo reconciles EXACTLY to the baseline (no false-positive
 * dirty hash), a genuine edit keeps the read-back values. Downscaling is
 * identity here — PhotoDownscaler has its own tests.
 */
class ProjectionReconcilerTest {

    @Test fun untouched_echo_reconciles_to_the_full_baseline() {
        val baseline = base()
        val echoRead = base().copy(
            structuredName = baseline.structuredName?.copy(additionalNames = listOf("Marie")),
            emails = listOf(ProjectedEmail("alice@example.org", emptyList(), true)),
            phones = listOf(
                ProjectedPhone("+1-111", listOf("cell"), false),
                ProjectedPhone("+1-222", listOf("work"), true),
            ),
            photo = ProjectedPhoto(byteArrayOf(1, 2, 3), mimeType = null), // the provider keeps only the bytes
        )

        val reconciled = ProjectionReconciler.reconcile(echoRead, baseline, downscale = { it })

        assertEquals(baseline, reconciled)
    }

    @Test fun a_type_flip_in_the_contacts_app_keeps_the_read_back_value() {
        val baseline = base()
        val read = base().copy(
            emails = listOf(ProjectedEmail("alice@example.org", listOf("work"), true)),
        )

        val reconciled = ProjectionReconciler.reconcile(read, baseline, downscale = { it })

        assertEquals(listOf("work"), reconciled.emails.single().types)
    }

    @Test fun a_deleted_entry_stays_deleted_and_a_new_entry_appends() {
        val baseline = base()
        val read = base().copy(
            phones = listOf(ProjectedPhone("+1-222", listOf("work"), true)), // +1-111 deleted
            urls = baseline.urls + "https://new.example",
        )

        val reconciled = ProjectionReconciler.reconcile(read, baseline, downscale = { it })

        assertEquals(listOf("+1-222"), reconciled.phones.map { it.number })
        assertEquals(listOf("https://base.example", "https://new.example"), reconciled.urls)
    }

    @Test fun fields_outside_the_provider_round_trip_ride_the_baseline() {
        val baseline = base()
        val read = base().copy(displayName = "Renamed")
        val reconciled = ProjectionReconciler.reconcile(read, baseline, downscale = { it })

        assertEquals("Renamed", reconciled.displayName)
        assertEquals(baseline.birthday, reconciled.birthday)
        assertEquals(baseline.anniversary, reconciled.anniversary)
    }

    @Test fun a_replaced_photo_keeps_the_read_back_bytes() {
        val baseline = base()
        val read = base().copy(photo = ProjectedPhoto(byteArrayOf(9, 9), mimeType = null))
        val reconciled = ProjectionReconciler.reconcile(read, baseline, downscale = { it })
        assertEquals(ProjectedPhoto(byteArrayOf(9, 9), null), reconciled.photo)
    }

    @Test fun a_missing_baseline_passes_the_read_through() {
        val read = base()
        val reconciled = ProjectionReconciler.reconcile(
            read,
            app.alpensync.contacts.vcard.ProjectedContact(
                protonContactId = "pc-1",
                protonUid = null,
                displayName = null,
                structuredName = null,
                emails = emptyList(),
                phones = emptyList(),
                addresses = emptyList(),
                organization = null,
                notes = emptyList(),
                imAccounts = emptyList(),
                photo = null,
                urls = emptyList(),
                birthday = null,
                anniversary = null,
            ),
            downscale = { it },
        )
        assertEquals(read.displayName, reconciled.displayName)
        assertEquals(read.emails, reconciled.emails)
        assertNull(reconciled.birthday)
    }

    private fun base() = app.alpensync.contacts.vcard.ProjectedContact(
        protonContactId = "pc-1",
        protonUid = "urn:uuid:pc-1",
        displayName = "Alice Example",
        structuredName = ProjectedName(
            given = "Alice",
            family = "Example",
            additionalNames = listOf("Marie", "Second"),
            prefixes = emptyList(),
            suffixes = emptyList(),
        ),
        emails = listOf(ProjectedEmail("alice@example.org", listOf("home"), false)),
        phones = listOf(
            ProjectedPhone("+1-111", listOf("cell"), false),
            ProjectedPhone("+1-222", listOf("work"), true),
        ),
        addresses = emptyList(),
        organization = null,
        notes = emptyList(),
        imAccounts = emptyList(),
        photo = ProjectedPhoto(byteArrayOf(1, 2, 3), mimeType = "image/jpeg"),
        urls = listOf("https://base.example"),
        birthday = "1990-01-01",
        anniversary = null,
    )
}
