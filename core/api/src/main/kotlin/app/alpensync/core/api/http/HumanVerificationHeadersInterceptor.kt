// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/HumanVerificationHeadersInterceptor.kt

package app.alpensync.core.api.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `x-pm-human-verification-token` and
 * `x-pm-human-verification-token-type` to every outgoing request when the
 * [tokens] source has a stored verification result. Once a user solves a
 * captcha and the token is persisted, every subsequent Proton API call
 * carries these headers until the token is cleared.
 *
 * Behavior mirrors protoncore's `ProtonApiBackend.kt::prepareHeaders`:
 * same two header names, same "set on every request when present" policy.
 */
class HumanVerificationHeadersInterceptor(
    private val tokens: HumanVerificationTokenSource,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.token()
        val type = tokens.tokenType()
        val request = if (!token.isNullOrBlank() && !type.isNullOrBlank()) {
            chain.request().newBuilder()
                .header("x-pm-human-verification-token", token)
                .header("x-pm-human-verification-token-type", type)
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
