// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// ADR 0007 Section 5, storage seam resolved as (i): the last-synced canonical
// vCard is persisted at rest so the write path can re-serialize losslessly
// and the ADR 0006 Option-B merge has a real ancestor. Decrypted contact
// content at rest is a threat-model change — the blob is Keystore-wrapped
// (AES-256-GCM, same construction as the M1 token store) by the store layer
// in :module-contacts; this table only ever holds ciphertext.

package app.alpensync.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One encrypted canonical vCard per (account, Proton contact). [vcardEnc] is
 * the Keystore-wrapped ciphertext of the full merged vCard 4.0 text (every
 * property incl. X-*, BDAY, URL, NICKNAME, CATEGORIES); [updatedAt] is the
 * wall-clock write time for diagnostics. Wiped on logout/account removal
 * alongside the KEK alias (THREAT_MODEL.md).
 */
@Entity(
    tableName = "canonical_vcards",
    primaryKeys = ["account_name", "proton_contact_id"],
)
data class CanonicalVCardEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "vcard_enc") val vcardEnc: ByteArray,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
