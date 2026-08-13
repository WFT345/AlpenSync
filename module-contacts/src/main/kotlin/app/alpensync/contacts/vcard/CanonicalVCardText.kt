// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.vcard

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import java.security.MessageDigest

/**
 * The canonical vCard 4.0 TEXT form — what the encrypted canonical store
 * persists (ADR 0007 Section 5(i)) and what
 * `contact_map.last_known_server_payload_hash` hashes. One deterministic
 * serialization shared by the pull engine (store on apply), the write engine
 * (store after push), and [ContactSerializer]'s card bodies, so a hash
 * comparison is never fooled by serializer drift.
 *
 * [parse] is fail-closed: the only writers of this text are this object and
 * Proton's own cards, so a parse failure means store corruption, not a
 * malformed wire payload — the caller treats it like a missing row.
 */
object CanonicalVCardText {

    fun write(vcard: VCard): String =
        Ezvcard.write(vcard).version(VCardVersion.V4_0).prodId(false).go().trimEnd()

    /** Throws [IllegalArgumentException] when [text] holds no parseable vCard. */
    // ez-vcard is lenient by design but can still throw arbitrary unchecked
    // exceptions on hostile/truncated input; any of them means this stored row
    // is unreadable, so the catch normalizes them all (fail-closed, Rule 5).
    @Suppress("TooGenericExceptionCaught")
    fun parse(text: String): VCard {
        val parsed = try {
            Ezvcard.parse(text).first()
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("canonical vCard text holds no vCard", e)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("canonical vCard text failed to parse", e)
        }
        return parsed
    }

    /** The divergence hint of ADR 0007 Section 5: sha-256 over the canonical text. */
    fun payloadHash(canonicalText: String): String =
        sha256Hex(canonicalText.toByteArray(Charsets.UTF_8))
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
