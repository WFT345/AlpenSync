// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/HeadersInterceptor.kt
// Deviation: no User-Agent spoofing and no x-pm-apiversion header (research notes
// Section 1 — pcontacts sends neither and is live-verified).

package app.alpensync.core.api.http

import app.alpensync.core.api.ProtonApiConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches Proton's mandatory request headers:
 *   - accept: application/vnd.protonmail.v1+json   (always)
 *   - x-pm-appversion: <appVersion>                (contract selector — see
 *     [ProtonApiConfig]; the accepted window is 2.0.0–3.0.12)
 *   - x-pm-locale: <locale>                        when configured
 *
 * Auth-specific headers (`x-pm-uid`, `Authorization`) are attached by
 * [AuthInterceptor]; human-verification headers by
 * [HumanVerificationHeadersInterceptor].
 */
class HeadersInterceptor(
    private val config: ProtonApiConfig,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("accept", ProtonApiConfig.ACCEPT_HEADER)
            .header("x-pm-appversion", config.appVersion)

        config.locale?.takeIf { it.isNotBlank() }?.let { builder.header("x-pm-locale", it) }

        return chain.proceed(builder.build())
    }
}
