// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/users/UsersDtos.kt

package app.alpensync.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shapes for the user-key endpoints that drive the post-SRP key unlock
 * chain (ADR 0004 Section 6):
 *
 *   1. GET core/v4/users         → User with Keys[].PrivateKey (armored)
 *   2. GET core/v4/keys/salts    → KeySalt per User.Keys[i].ID
 *   3. keyPassword = bcrypt(password, salt-for-primary-key)[29:]
 *   4. unlock PrivateKey armored blocks with that passphrase
 *
 * Field names and types confirmed by pcontacts against WebClients
 * `packages/shared/lib/interfaces/User.ts` + live fetch (2026-05-24).
 */

@Serializable
data class UserKeyDto(
    @SerialName("ID") val id: String,
    @SerialName("Version") val version: Int = 0,
    @SerialName("Primary") val primary: Int = 0,
    @SerialName("Active") val active: Int = 1,
    @SerialName("PrivateKey") val privateKey: String,
    @SerialName("Fingerprint") val fingerprint: String = "",
    @SerialName("Flags") val flags: Int = 0,
)

@Serializable
data class UserDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("DisplayName") val displayName: String = "",
    @SerialName("Email") val email: String = "",
    @SerialName("Keys") val keys: List<UserKeyDto> = emptyList(),
)

@Serializable
data class GetUserResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class KeySaltDto(
    @SerialName("ID") val keyId: String,
    @SerialName("KeySalt") val keySalt: String? = null,
)

@Serializable
data class GetKeySaltsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("KeySalts") val keySalts: List<KeySaltDto> = emptyList(),
)
