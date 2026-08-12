// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/OkHttpClientFactory.kt
// Deliberate deviations from pcontacts:
//  - NO certificate pinning (plan Section 3.6): users must be able to audit
//    traffic with a proxy. The app-level networkSecurityConfig still
//    restricts traffic to Proton hosts.
//  - No DNS guard: host lockdown is the app module's networkSecurityConfig
//    job; duplicating it here would break MockWebServer-less unit wiring.

package app.alpensync.core.api.http

import app.alpensync.core.api.ProtonApiConfig
import app.alpensync.core.api.Session
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.OkHttpClient

/**
 * The single `OkHttpClient` factory the app uses. Layers applied to every
 * request, in order:
 *   - [HeadersInterceptor]                — accept, x-pm-appversion
 *   - [HumanVerificationHeadersInterceptor] — HV token headers when present
 *   - [AuthInterceptor]                   — x-pm-uid + Authorization
 *   - [FibonacciBackoffInterceptor]       — 429 retry with Fibonacci backoff
 *   - [HumanVerificationInterceptor]      — Code 9001/12087 → typed error
 *   - [AppVersionRejectionInterceptor]    — Code 5003/5004 → typed error
 *   - [Authenticator] (optional)          — 401 → refresh → replay; MUST be
 *     null on the refresh-only client (recursion guard).
 *
 * No HTTP logging interceptor: request/response bodies carry credentials
 * and tokens (plan Rule 1). Debug logging, if ever added, goes through
 * SafeLog events only.
 */
object OkHttpClientFactory {

    fun create(
        config: ProtonApiConfig,
        session: Session,
        authenticator: Authenticator? = null,
        humanVerificationTokens: HumanVerificationTokenSource = HumanVerificationTokenSource.Empty,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(config))
            .addInterceptor(HumanVerificationHeadersInterceptor(humanVerificationTokens))
            .addInterceptor(AuthInterceptor(session))
            // Application-layer (not network) interceptor: the request is
            // already stamped with headers + auth when backoff sees it, so
            // retries replay the same authenticated request.
            .addInterceptor(FibonacciBackoffInterceptor())
            .addInterceptor(HumanVerificationInterceptor(tokens = humanVerificationTokens))
            // Must run after the 9001 check — human verification is the
            // more specific condition with its own recovery path.
            .addInterceptor(AppVersionRejectionInterceptor())
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        if (authenticator != null) builder.authenticator(authenticator)
        return builder.build()
    }

    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 30L
    private const val CALL_TIMEOUT_S = 60L
}
