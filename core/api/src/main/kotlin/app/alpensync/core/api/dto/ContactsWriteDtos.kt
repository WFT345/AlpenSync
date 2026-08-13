// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/.../contacts/ContactsDtos.kt (write-path half,
// [V]-verified against WebClients packages/shared/lib/api/contacts.ts).
// Own file so the M2 read shapes stay untouched; shapes per research notes
// Section 2.1-2.3. First own live contact is the M3c run.

package app.alpensync.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the contacts WRITE endpoints. Same strict-parsing posture
 * as the read DTOs (plan Rule 5): identity fields of sub-responses have no
 * defaults so a malformed row fails deserialization loudly; envelope fields
 * degrade via defaults plus the factory's `ignoreUnknownKeys = true`.
 *
 * Partial failure is per-item, never per-HTTP-status: a bulk call returns
 * HTTP 200 with per-index/per-ID sub-`Code`s, so callers must walk the
 * sub-responses (the helpers below) — 1000 is Proton's success code.
 */
private const val CODE_SUCCESS = 1000

/** One contact's card set inside a create batch. We always send single-item batches (ADR 0007 Section 4). */
@Serializable
data class ContactCardBundle(
    @SerialName("Cards") val cards: List<ContactCardDto>,
)

/**
 * `POST contacts/v4/contacts` body. `Overwrite`/`Labels` stay 0 (no import
 * semantics, no label assignment — groups are read-only, research notes
 * Section 2.5).
 */
@Serializable
data class CreateContactsRequest(
    @SerialName("Contacts") val contacts: List<ContactCardBundle>,
    @SerialName("Overwrite") val overwrite: Int = 0,
    @SerialName("Labels") val labels: Int = 0,
)

@Serializable
data class CreateContactResponseBody(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contact") val contact: ContactDto? = null,
)

/** Per-index sub-response; [response] is required — a missing one fails closed. */
@Serializable
data class CreateContactResponseItem(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Response") val response: CreateContactResponseBody,
)

@Serializable
data class CreateContactsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Responses") val responses: List<CreateContactResponseItem> = emptyList(),
)

/** Indexes whose sub-response Code is not success (single-item batches make this trivially walked). */
fun CreateContactsResponse.failedIndexes(): List<Int> =
    responses.filter { it.response.code != CODE_SUCCESS }.map { it.index }

/**
 * `PUT contacts/v4/contacts/{id}` body — replaces the ENTIRE Cards[] array
 * server-side (research notes Section 2.2). This is the API-level fact that
 * makes write-path losslessness mandatory: anything not re-serialized is
 * deleted server-side.
 */
@Serializable
data class UpdateContactRequest(
    @SerialName("Cards") val cards: List<ContactCardDto>,
)

@Serializable
data class UpdateContactResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contact") val contact: ContactDto? = null,
)

/**
 * `PUT contacts/v4/contacts/delete` body — genuinely PUT, not DELETE, and
 * there is no server-side Trash: a pushed delete is final (research notes
 * Section 2.3). We send one ID per call (ADR 0007 Section 4).
 */
@Serializable
data class BulkDeleteRequest(
    @SerialName("IDs") val ids: List<String>,
)

@Serializable
data class DeleteResponseBody(
    @SerialName("Code") val code: Int = 0,
)

/** Per-ID sub-response; [id] is required — a missing one fails closed. */
@Serializable
data class DeleteResponseItem(
    @SerialName("ID") val id: String,
    @SerialName("Response") val response: DeleteResponseBody,
)

@Serializable
data class BulkDeleteResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Responses") val responses: List<DeleteResponseItem> = emptyList(),
)

/** IDs whose sub-response Code is not success — the only error channel this endpoint has. */
fun BulkDeleteResponse.failedIds(): List<String> =
    responses.filter { it.response.code != CODE_SUCCESS }.map { it.id }
