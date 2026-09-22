// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// New at the 2026-09 audit (finding M1) — no pcontacts counterpart. It
// replaces the host-lockdown role this codebase wrongly attributed to the
// app module's networkSecurityConfig: Android's network security config
// can only regulate cleartext and trust anchors per domain, it CANNOT
// restrict which hosts an app contacts. pcontacts carried a DNS guard for
// the same invariant; this is the OkHttp-native equivalent, and unlike a
// DNS guard it needs no test-mode escape hatch — the allowed origin is
// derived from [ProtonApiConfig.baseUrl], which tests point at their
// MockWebServer.

package app.alpensync.core.api.http

import app.alpensync.core.api.ProtonApiConfig
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * A request tried to leave the configured API origin. Deliberately carries
 * only the offending host — never the full URL, whose path/query could in
 * principle hold request data (plan Rule 1).
 */
class DisallowedHostException(host: String) :
    IOException("request blocked: host '$host' is not the configured Proton API origin")

/**
 * Confines every request to the origin of [ProtonApiConfig.baseUrl]
 * (scheme + host + port). Any request aimed elsewhere dies with
 * [DisallowedHostException] before a byte leaves the client — the
 * invariant being that the stamped `x-pm-uid`, `Authorization`, and
 * human-verification headers can never travel to a non-Proton host,
 * redirects included.
 *
 * [OkHttpClientFactory] registers ONE instance of this class TWICE:
 *   - as the first application interceptor, so a mis-aimed call fails
 *     fast, before a connection is opened or headers are stamped; and
 *   - as a network interceptor, which sees every wire request OkHttp
 *     synthesizes — including redirect follow-ups, so the invariant holds
 *     even if redirect-following (off in the factory) were ever
 *     re-enabled.
 *
 * The full-origin comparison matters: matching the host alone would let a
 * same-host redirect walk credentials to a different port/scheme, and
 * would make the guard untestable against two MockWebServers (both
 * `localhost`, distinct ports).
 */
class HostGuardInterceptor(config: ProtonApiConfig) : Interceptor {

    private val allowed: HttpUrl = config.baseUrl.toHttpUrl()

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        // HttpUrl.port is the effective port (443 for a portless https
        // base URL), so implicit and explicit defaults compare equal.
        if (url.scheme != allowed.scheme || url.host != allowed.host || url.port != allowed.port) {
            throw DisallowedHostException(url.host)
        }
        return chain.proceed(chain.request())
    }
}
