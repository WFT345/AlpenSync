// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/contacts/ContactsDtos.kt
// Deviation: M2 read path only — the write-path DTOs (create/update/delete) land at M3.
// The `contacts/v4/contacts/emails` endpoint is skipped entirely at M2
// (docs/research/m2-contacts-notes.md Section 1.4).

package app.alpensync.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the Proton contacts endpoints. Field names match the JSON
 * exactly; capitalization matters. Shapes confirmed against WebClients
 * `packages/shared/lib/api/contacts.ts` and live-validated by pcontacts
 * 2026-05-24 (research notes Section 1.1/1.2); not yet re-verified by us —
 * first contact with the live endpoints is the M2 acceptance run.
 *
 * Strict-parsing posture (plan Rule 5): required identity fields (`ID`) have
 * no default, so a malformed row fails deserialization loudly instead of
 * propagating a blank key; everything optional degrades via defaults and the
 * factory's `ignoreUnknownKeys = true`.
 */

@Serializable
data class ContactCardDto(
    @SerialName("Type") val type: Int,
    @SerialName("Data") val data: String,
    @SerialName("Signature") val signature: String? = null,
)

/**
 * Per-email denormalized row carried on the full contact. M2 does not query
 * the standalone emails endpoint; these arrive inside `ContactDto` only.
 */
@Serializable
data class ContactEmailDto(
    @SerialName("ID") val id: String,
    @SerialName("Email") val email: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: List<String> = emptyList(),
    @SerialName("Defaults") val defaults: Int = 0,
    @SerialName("Order") val order: Int = 0,
    @SerialName("ContactID") val contactId: String,
    @SerialName("LabelIDs") val labelIds: List<String> = emptyList(),
    @SerialName("LastUsedTime") val lastUsedTime: Long = 0L,
)

/**
 * Full contact from `GET contacts/v4/contacts/{id}` — the metadata fields
 * plus `Cards[]` (the encrypted/signed vCard fragments) and denormalized
 * `ContactEmails[]`.
 */
@Serializable
data class ContactDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("UID") val uid: String = "",
    @SerialName("Size") val size: Long = 0L,
    @SerialName("CreateTime") val createTime: Long = 0L,
    @SerialName("ModifyTime") val modifyTime: Long = 0L,
    @SerialName("Cards") val cards: List<ContactCardDto> = emptyList(),
    @SerialName("ContactEmails") val contactEmails: List<ContactEmailDto> = emptyList(),
    @SerialName("LabelIDs") val labelIds: List<String> = emptyList(),
)

@Serializable
data class GetContactResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contact") val contact: ContactDto,
)

/** Metadata row from the cheap listing endpoint — no `Cards[]`. */
@Serializable
data class ContactMetadataDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("UID") val uid: String = "",
    @SerialName("Size") val size: Long = 0L,
    @SerialName("CreateTime") val createTime: Long = 0L,
    @SerialName("ModifyTime") val modifyTime: Long = 0L,
    @SerialName("LabelIDs") val labelIds: List<String> = emptyList(),
)

/**
 * One listing page. `total` is modeled but intentionally NOT consumed by the
 * pager: it can lag mid-pagination when contacts are added/removed during the
 * walk (research notes Section 1.1).
 */
@Serializable
data class ContactsPageResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contacts") val contacts: List<ContactMetadataDto> = emptyList(),
    @SerialName("Total") val total: Int = 0,
)
