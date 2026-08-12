// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/.../contacts/ContactsMetadataPager.kt

package app.alpensync.core.api

import app.alpensync.core.api.dto.ContactMetadataDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Walks every page of `contacts/v4/contacts` (the cheap metadata endpoint —
 * no `Cards[]`) and emits each [ContactMetadataDto] exactly once. Cold flow:
 * restarting collection starts a fresh server-side scan.
 *
 * Paging semantics (research notes Section 1.1, live-validated by pcontacts
 * 2026-05-24):
 *   - Pages are 0-indexed.
 *   - Stop when a page returns fewer items than the requested page size (or
 *     is empty).
 *   - `Total` is intentionally ignored — it can lag mid-pagination when a
 *     contact is added/removed during the walk.
 */
class ContactsMetadataPager(
    private val api: ProtonApi,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize out of range: $pageSize" }
    }

    fun metadata(labelIdFilter: String? = null): Flow<ContactMetadataDto> = flow {
        var page = 0
        while (true) {
            val response = api.listContacts(page = page, pageSize = pageSize, labelIdFilter = labelIdFilter)
            response.contacts.forEach { emit(it) }
            if (response.contacts.size < pageSize) return@flow
            page += 1
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 1000
        const val MAX_PAGE_SIZE = 1000
    }
}
