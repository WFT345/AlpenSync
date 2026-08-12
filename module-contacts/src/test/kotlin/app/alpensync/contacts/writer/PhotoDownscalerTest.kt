// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/src/test/.../PhotoDownscalerTest.kt

package app.alpensync.contacts.writer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure no-op/null paths only: Robolectric's Bitmap shadow is brittle around
 * compress() fidelity, so the real decode/resize path is covered by the
 * instrumented photo round-trip on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PhotoDownscalerTest {

    @Test
    fun empty_input_returns_null() {
        assertNull(PhotoDownscaler.downscale(ByteArray(0)))
    }

    @Test
    fun bytes_already_under_the_cap_pass_through_unchanged() {
        val small = ByteArray(100) { it.toByte() }
        assertSame("under-cap input must not be re-encoded", small, PhotoDownscaler.downscale(small))
    }

    @Test
    fun cap_constant_matches_the_contactscontract_inline_photo_limit() {
        assertEquals(96 * 1024, PhotoDownscaler.MAX_INLINE_PHOTO_BYTES)
    }
}
