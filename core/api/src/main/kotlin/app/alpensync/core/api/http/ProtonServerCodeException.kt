// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api.http

import app.alpensync.core.api.log.SafeLog
import java.io.IOException
import retrofit2.HttpException

/**
 * Fixed vocabulary of API call stages for [ProtonServerCodeException]
 * (ADR 0004 Section 2 error taxonomy). A constant per call site — NEVER a
 * URL: URLs can carry query parameters or user data (plan Rule 1), an enum
 * constant cannot. Entries cover every endpoint family the suite calls;
 * families not yet wrapped (REFRESH, CONTACTS) exist so future call sites
 * never invent ad-hoc stage strings.
 */
enum class EndpointFamily {
    AUTH_INFO,
    AUTH,
    AUTH_2FA,
    REFRESH,
    USERS,
    KEYS_SALTS,
    ADDRESSES,
    CONTACTS,
    OTHER,
}

/**
 * Fallback typed error for HTTP failures the typed interceptors did NOT
 * claim (9001/12087 → human verification, 5003/5004 → app-version rejection
 * keep their own paths and never reach this type). [protonCode] is the JSON
 * body's integer `Code` when one was present, null for non-JSON or
 * unparseable bodies; [endpointFamily] names the failing call stage and
 * [httpStatus] the HTTP status. The message deliberately carries no URL,
 * body, or headers (Rule 1). Live test 1 (2026-08-12): a post-captcha 422
 * with an unmapped Code escaped as a raw [HttpException] and crashed the
 * login — this type is the containment.
 */
class ProtonServerCodeException(
    val protonCode: Int?,
    val endpointFamily: EndpointFamily,
    val httpStatus: Int,
    cause: HttpException,
) : IOException("Proton returned error code ${protonCode ?: "unknown"} at $endpointFamily (HTTP $httpStatus)", cause)

/**
 * Call-boundary mapping (ADR 0004 Section 2): wraps a Retrofit call so an
 * HTTP error that passed every typed interceptor surfaces as
 * [ProtonServerCodeException] instead of a raw [HttpException] (a
 * RuntimeException the `catch (IOException)` clauses upstream cannot see).
 * The `Code` is peeked size-capped and parsed leniently; any parse failure
 * yields the null-code variant (fail-closed, plan Rule 5). Known codes never
 * reach here — the interceptors throw first — so their behavior is
 * unchanged; non-HTTP failures (IO, serialization) pass through untouched.
 */
suspend fun <T> mapServerCodes(family: EndpointFamily, call: suspend () -> T): T = try {
    call()
} catch (e: HttpException) {
    val code = e.peekProtonCode()
    if (code != null) {
        SafeLog.log(SafeLog.Event.SERVER_CODE, code)
    } else {
        SafeLog.log(SafeLog.Event.SERVER_CODE_STAGE, e.code())
    }
    throw ProtonServerCodeException(code, family, e.code(), e)
}

/** Size-capped, fail-closed `Code` extraction from the unconsumed error body. */
private fun HttpException.peekProtonCode(): Int? {
    val body = response()?.errorBody() ?: return null
    return try {
        val source = body.source()
        source.request(MAX_PEEK_BYTES)
        val buffered = source.buffer
        AppVersionRejectionInterceptor.extractCode(
            buffered.readString(minOf(buffered.size, MAX_PEEK_BYTES), Charsets.UTF_8),
        )
    } catch (ignored: IOException) {
        null
    } finally {
        runCatching { body.close() }
    }
}

private const val MAX_PEEK_BYTES: Long = 8 * 1024
