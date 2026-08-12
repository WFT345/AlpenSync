// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/retrofit/ProtonApiFactory.kt
// Deviation: one [ProtonApi] surface instead of per-area interfaces; the
// RefreshConfig gains an onSessionInvalid callback (invalid-grant → wipe).

package app.alpensync.core.api

import app.alpensync.core.api.http.HumanVerificationTokenSource
import app.alpensync.core.api.http.OkHttpClientFactory
import app.alpensync.core.api.http.RefreshingAuthenticator
import app.alpensync.core.api.http.TokenRefresher
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Assembles the Retrofit instance and exposes the typed [ProtonApi]
 * surface. The JSON config is explicit so the wire shape can't drift
 * silently across kotlinx-serialization upgrades.
 *
 * When [refreshConfig] is supplied, the factory builds in two stages:
 *   1. A refresh-only OkHttpClient with NO authenticator — used solely
 *      for the `auth/refresh` call so the authenticator can't recurse.
 *   2. The main OkHttpClient with [RefreshingAuthenticator] wired against
 *      the stage-1 refresh API.
 *
 * When [refreshConfig] is null (tests, the pre-login bootstrap path), the
 * single-stage client without authenticator is used.
 */
class ProtonApiFactory(
    config: ProtonApiConfig,
    session: Session,
    refreshConfig: RefreshConfig? = null,
    humanVerificationTokens: HumanVerificationTokenSource = HumanVerificationTokenSource.Empty,
) {

    /**
     * Caller-supplied 401 → refresh wiring. The factory keeps `:core:api`
     * independent of `:core:auth`'s storage by taking the persistence side
     * as callbacks.
     */
    data class RefreshConfig(
        /** Same instance carried by the read-only `session` param. */
        val mutableSession: InMemorySession,
        /** Returns the current refresh token, or null if logged out. */
        val getRefreshToken: () -> String?,
        /** Persist the freshly-rotated tokens (typically into SecretStore). */
        val onTokensRefreshed: (accessToken: String, refreshToken: String) -> Unit,
        /**
         * Fired when the server rejects the refresh token itself
         * (invalid grant, 401/422): the caller wipes the session and
         * returns to the login state.
         */
        val onSessionInvalid: () -> Unit = {},
    )

    private val json: Json = Json {
        ignoreUnknownKeys = true        // Server adds fields without notice; tolerate them.
        explicitNulls = false           // Proton omits nulls from JSON.
        coerceInputValues = true        // Tolerate missing optional numeric/list fields.
        encodeDefaults = true           // DTO defaults (e.g. Intent = "Proton") must reach the wire.
    }

    // Stage 1: refresh-only client — no authenticator, so auth/refresh can
    // never recurse through the 401 handler.
    private val refreshOnlyClient: OkHttpClient = OkHttpClientFactory.create(
        config = config,
        session = session,
        authenticator = null,
        humanVerificationTokens = humanVerificationTokens,
    )
    private val refreshOnlyApi: ProtonApi =
        buildRetrofit(config, refreshOnlyClient).create(ProtonApi::class.java)

    // Stage 2: main client, with the authenticator when refresh is wired.
    private val mainClient: OkHttpClient = if (refreshConfig != null) {
        val refresher = TokenRefresher(
            refreshOnlyApi = refreshOnlyApi,
            mutableSession = refreshConfig.mutableSession,
            getRefreshToken = refreshConfig.getRefreshToken,
            onTokensRefreshed = refreshConfig.onTokensRefreshed,
            onSessionInvalid = refreshConfig.onSessionInvalid,
        )
        OkHttpClientFactory.create(
            config = config,
            session = session,
            authenticator = RefreshingAuthenticator(refresher, refreshConfig.mutableSession),
            humanVerificationTokens = humanVerificationTokens,
        )
    } else {
        refreshOnlyClient
    }

    val api: ProtonApi = buildRetrofit(config, mainClient).create(ProtonApi::class.java)

    private fun buildRetrofit(config: ProtonApiConfig, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
