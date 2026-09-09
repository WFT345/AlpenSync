// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.writer

import android.content.ContentProviderResult
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BatchApplierTest {

    @Test
    fun harvest_maps_each_create_slot_to_its_result_uri() {
        val slots = listOf(CreateSlot(opIndex = 0, sourceId = "c1"), CreateSlot(opIndex = 3, sourceId = "c2"))
        val results = arrayOf(
            ContentProviderResult(Uri.parse("content://com.android.contacts/raw_contacts/101")),
            ContentProviderResult(1),
            ContentProviderResult(1),
            ContentProviderResult(Uri.parse("content://com.android.contacts/raw_contacts/102")),
        )
        assertEquals(mapOf("c1" to 101L, "c2" to 102L), harvestCreatedRawIds(slots, results))
    }

    @Test
    fun a_create_without_a_result_uri_is_skipped_not_invented() {
        // The engine records the contact as provider_write_missing downstream;
        // a guessed ID would corrupt the mapping table instead.
        val slots = listOf(CreateSlot(opIndex = 0, sourceId = "c1"))
        assertTrue(harvestCreatedRawIds(slots, arrayOf(ContentProviderResult(1))).isEmpty())
        assertTrue(harvestCreatedRawIds(slots, emptyArray()).isEmpty())
    }
}
