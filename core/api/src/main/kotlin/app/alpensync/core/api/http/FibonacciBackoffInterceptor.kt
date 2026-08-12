// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/FibonacciBackoffInterceptor.kt
// Deviation: SafeLog instead of pcontacts' Logger.

package app.alpensync.core.api.http

import app.alpensync.core.api.log.SafeLog
import okhttp3.Interceptor
import okhttp3.Response

/**
 * When Proton rate-limits a call (HTTP 429), retry with Fibonacci backoff,
 * honoring the `Retry-After` header when present (it overrides the backoff
 * schedule). Cap at [maxRetries] so a stuck account doesn't pin a
 * background thread forever — after the cap the 429 propagates to the
 * caller.
 *
 * POST requests are never retried here: they are not idempotent (login,
 * 2FA, refresh), so replaying them blindly could double-submit.
 *
 * [sleeper] is injected so tests verify the schedule without real sleeps.
 */
class FibonacciBackoffInterceptor(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method == "POST") return chain.proceed(request)

        var attempt = 0
        var response = chain.proceed(request)

        while (response.code == HTTP_TOO_MANY_REQUESTS && attempt < maxRetries) {
            val retryAfterMs = response.header(RETRY_AFTER)?.toLongOrNull()?.times(1000)
            val sleepMs = retryAfterMs ?: fibonacciMillis(attempt)
            response.close()
            SafeLog.log(SafeLog.Event.RATE_LIMITED_RETRYING, attempt + 1)
            sleeper(sleepMs)
            attempt += 1
            response = chain.proceed(request)
        }

        if (response.code == HTTP_TOO_MANY_REQUESTS) {
            SafeLog.log(SafeLog.Event.RATE_LIMITED_GIVING_UP, maxRetries)
        }
        return response
    }

    /**
     * 1s, 2s, 3s, 5s, 8s, ... — the Fibonacci sequence in milliseconds.
     * n=0 returns 1000ms; each subsequent step adds the previous two.
     */
    internal fun fibonacciMillis(n: Int): Long {
        require(n >= 0) { "n must be non-negative" }
        var a = 1L
        var b = 2L
        repeat(n) {
            val next = a + b
            a = b
            b = next
        }
        return a * 1000L
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 5
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val RETRY_AFTER = "Retry-After"
    }
}
