// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/RefreshingAuthenticator.kt

package app.alpensync.core.api.http

import app.alpensync.core.api.InMemorySession
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp Authenticator that intercepts 401 responses, runs
 * `auth/refresh` via [TokenRefresher] (single-flight), and resubmits the
 * original request with the freshly-issued bearer token. Returns null on
 * the second 401 in a row (bounded retry, standard OkHttp idiom —
 * prevents infinite refresh loops).
 *
 * The bearer header is overwritten on the resubmit; [AuthInterceptor]
 * would otherwise re-stamp it from the session and could win or lose the
 * race depending on interceptor order. Setting the header explicitly here
 * pins it to the freshly-refreshed value.
 */
class RefreshingAuthenticator(
    private val refresher: TokenRefresher,
    private val mutableSession: InMemorySession,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) > 1) return null

        val tokenAtStart = response.request
            .header(AUTHORIZATION_HEADER)
            ?.removePrefix(BEARER_PREFIX)
        if (!refresher.refreshIfStillStale(tokenAtStart)) return null

        val newToken = mutableSession.accessToken() ?: return null
        return response.request.newBuilder()
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + newToken)
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count += 1
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
