// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/contacts-writer/.../PhotoDownscaler.kt

package app.alpensync.contacts.writer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Downscales contact photo bytes to fit ContactsContract's inline
 * `Photo.PHOTO` BLOB column (~96 KB soft cap — larger payloads silently fail
 * at the provider).
 *
 * Strategy: pass through when already small enough; otherwise re-encode as
 * JPEG sliding quality down, then halve dimensions, and drop the photo
 * entirely rather than break the whole RawContact apply.
 *
 * M3 trap (research notes Section 1.6): the DOWNSCALED bytes land on the
 * phone — M3 write-back must re-fetch the server photo and never push these
 * degraded bytes back.
 */
object PhotoDownscaler {

    /** ContactsContract's documented soft cap for the inline Photo column. */
    const val MAX_INLINE_PHOTO_BYTES = 96 * 1024

    private const val MIN_JPEG_QUALITY = 40
    private const val MIN_DIMENSION = 64

    /**
     * @return bytes ≤ [MAX_INLINE_PHOTO_BYTES] for the inline Photo column,
     *         or null when the input is undecodable or can't be compressed
     *         small enough (better no photo than a broken apply).
     */
    fun downscale(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        if (bytes.size <= MAX_INLINE_PHOTO_BYTES) return bytes

        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            tryFit(original)
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun tryFit(original: Bitmap): ByteArray? {
        var width = original.width
        var height = original.height
        var bitmap = original

        while (true) {
            for (quality in 90 downTo MIN_JPEG_QUALITY step 10) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val candidate = out.toByteArray()
                if (candidate.size <= MAX_INLINE_PHOTO_BYTES) return candidate
            }
            width /= 2
            height /= 2
            if (width < MIN_DIMENSION || height < MIN_DIMENSION) return null
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (bitmap !== original && !bitmap.isRecycled) bitmap.recycle()
            bitmap = scaled
        }
    }
}
