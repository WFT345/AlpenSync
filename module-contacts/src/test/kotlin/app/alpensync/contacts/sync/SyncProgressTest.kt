// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProgressTest {

    @After
    fun tearDown() {
        SyncProgressHub.reset()
    }

    @Test
    fun fraction_is_null_until_listing_finishes() {
        assertNull(SyncProgress(listed = 0, processed = 0, inFlight = true).fraction)
        assertEquals(0.5f, SyncProgress(listed = 4, processed = 2, inFlight = true).fraction)
        assertEquals(1f, SyncProgress(listed = 3, processed = 3, inFlight = true).fraction)
    }

    @Test
    fun hub_notifies_current_and_later_snapshots() {
        val seen = mutableListOf<SyncProgress>()
        val listener: (SyncProgress) -> Unit = { seen += it }
        SyncProgressHub.addListener(listener)
        assertEquals(listOf(SyncProgress()), seen)

        val live = SyncProgress(listed = 9, processed = 3, applied = 3, inFlight = true)
        SyncProgressHub.publish(live)
        assertEquals(live, SyncProgressHub.latest)
        assertEquals(live, seen.last())

        SyncProgressHub.removeListener(listener)
        SyncProgressHub.publish(SyncProgress(listed = 9, processed = 4, inFlight = true))
        assertEquals(2, seen.size)
    }
}
