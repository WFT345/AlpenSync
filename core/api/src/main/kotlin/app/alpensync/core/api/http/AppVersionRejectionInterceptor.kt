// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/AppVersionRejectionInterceptor.kt
// Deviation: SafeLog instead of pcontacts' Logger; narrowed catch clause.

package app.alpensync.core.api.http

import app.alpensync.core.api.log.SafeLog
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Detects Proton's version-rejected responses and surfaces them as a typed
 * [AppVersionRejectedException] — fail loud, never retried. This lets
 * callers distinguish "our pinned `x-pm-appversion` aged out of Proton's
 * sliding acceptance window" from generic auth or IO failures.
 *
 * Detection keys on the JSON `Code` field, NOT the HTTP status (which
 * varies 400/401/422): 5003 = force upgrade (live-verified), 5004 = API
 * version unsupported (assumed).
 *
 * This interceptor MUST run after [HumanVerificationInterceptor] in the
 * chain so that 9001 is caught first (more specific, own recovery path).
 */
class AppVersionRejectionInterceptor(
    private val maxPeekBytes: Long = DEFAULT_MAX_PEEK_BYTES,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.body.contentType()?.subtype ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return response

        val snippet = try {
            response.peekBody(maxPeekBytes).string()
        } catch (ignored: IOException) {
            SafeLog.log(SafeLog.Event.RESPONSE_BODY_UNREADABLE)
            return response
        }

        val code = extractCode(snippet) ?: return response
        if (code in VERSION_REJECTION_CODES) {
            response.close()
            SafeLog.log(SafeLog.Event.APP_VERSION_REJECTED, code)
            throw AppVersionRejectedException(code)
        }
        return response
    }

    companion object {
        const val DEFAULT_MAX_PEEK_BYTES: Long = 8 * 1024

        // 5003 = AppVersionBadAppVersion (force upgrade) [live-verified];
        // 5004 = AppVersionBadApiVersion (API version unsupported) [assumed].
        val VERSION_REJECTION_CODES: Set<Int> = setOf(5003, 5004)

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun extractCode(body: String): Int? = try {
            lenientJson.parseToJsonElement(body)
                .jsonObject["Code"]
                ?.jsonPrimitive
                ?.int
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Proton rejected the `x-pm-appversion` header — the pinned version aged
 * out of the acceptance window. NOT transient: retrying will not help; the
 * app needs an update that bumps [app.alpensync.core.api.ProtonApiConfig].
 */
class AppVersionRejectedException(
    val protonCode: Int,
    message: String = "Proton rejected x-pm-appversion (Code $protonCode) — app update required",
) : IOException(message)
