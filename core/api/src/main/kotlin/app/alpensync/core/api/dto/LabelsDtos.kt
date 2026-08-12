// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/labels/LabelsDtos.kt

package app.alpensync.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for `GET core/v4/labels?Type=...`. Field names confirmed
 * against WebClients `packages/shared/lib/interfaces/Label.ts` (research
 * notes Section 1.5). Only the fields the contact-groups path needs are
 * modeled; unknown fields are tolerated by the parser config.
 *
 * M2 uses groups read-only (ADR 0005 open question 4): labels are fetched so
 * `LabelIDs` on contacts can later be reconciled into ContactsContract.Groups
 * rows; group management stays web-only even in pcontacts' bidirectional
 * phase.
 */
@Serializable
data class LabelDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ParentID") val parentId: String? = null,
    @SerialName("Type") val type: Int = 0,
    @SerialName("Path") val path: String? = null,
)

@Serializable
data class GetLabelsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Labels") val labels: List<LabelDto> = emptyList(),
)

/** Proton's label Type taxonomy (WebClients `packages/shared/lib/constants.ts` LABEL_TYPE). */
object LabelType {
    const val MAIL_LABEL = 1
    const val CONTACT_GROUP = 2
    const val MAIL_FOLDER = 3
    const val CONTACT_GROUP_SUB = 4
}
