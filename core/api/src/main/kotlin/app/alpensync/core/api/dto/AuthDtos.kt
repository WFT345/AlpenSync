// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/auth/AuthDtos.kt

package app.alpensync.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level shapes for the Proton auth endpoints. Field names match the
 * JSON exactly; capitalization matters. Live-verified by pcontacts against
 * the production API (2026-05-24); see docs/research/m1-auth-api-notes.md.
 *
 * Strict parsing (plan Rule 5): required fields have no defaults, unknown
 * keys are ignored by the parser config in ProtonApiFactory.
 */

@Serializable
data class InfoRequest(
    @SerialName("Username") val username: String,
    @SerialName("Intent") val intent: String = "Proton",
)

@Serializable
data class InfoResponse(
    @SerialName("Modulus") val modulus: String,
    @SerialName("ServerEphemeral") val serverEphemeral: String,
    @SerialName("Version") val version: Int,
    @SerialName("Salt") val salt: String,
    @SerialName("SRPSession") val srpSession: String,
    @SerialName("Code") val code: Int = 0,
)

@Serializable
data class AuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("ClientEphemeral") val clientEphemeral: String,
    @SerialName("ClientProof") val clientProof: String,
    @SerialName("SRPSession") val srpSession: String,
    // Live-verified by pcontacts (2026-05-24): an empty ChallengePayload map
    // is accepted. If auth starts failing here this is the first suspect.
    @SerialName("Payload") val payload: Map<String, String>? = null,
    @SerialName("PersistentCookies") val persistentCookies: Int? = null,
)

@Serializable
data class AuthResponse(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("TokenType") val tokenType: String,
    @SerialName("ExpiresIn") val expiresIn: Long,
    @SerialName("UID") val uid: String,
    @SerialName("UserID") val userId: String,
    @SerialName("LocalID") val localId: Long? = null,
    @SerialName("PasswordMode") val passwordMode: Int,
    @SerialName("TwoFactor") val twoFactor: Int,
    @SerialName("ServerProof") val serverProof: String,
    @SerialName("Scopes") val scopes: List<String> = emptyList(),
    @SerialName("Code") val code: Int = 0,
)

@Serializable
data class TwoFactorRequest(
    @SerialName("TwoFactorCode") val twoFactorCode: String,
)

/**
 * Response to `core/v4/auth/2fa`. The server does NOT re-issue tokens — it
 * elevates the existing session's scope server-side and returns only
 * `{Code, Scopes}` (docs/research/m1-auth-api-notes.md Section 3).
 */
@Serializable
data class TwoFactorResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Scopes") val scopes: List<String> = emptyList(),
)

@Serializable
data class RefreshRequest(
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("ResponseType") val responseType: String = "token",
    @SerialName("GrantType") val grantType: String = "refresh_token",
)

@Serializable
data class RefreshResponse(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("TokenType") val tokenType: String,
    @SerialName("ExpiresIn") val expiresIn: Long,
    @SerialName("UID") val uid: String,
    @SerialName("Scopes") val scopes: List<String> = emptyList(),
    @SerialName("Code") val code: Int = 0,
)
