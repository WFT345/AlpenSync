// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/HumanVerificationInterceptor.kt
// Deviations: SafeLog instead of pcontacts' Logger; catch clauses narrowed to
// checked/serialization types because detekt (plan Rule 7) bans catching
// Exception/Throwable. The Details field names (HumanVerificationToken /
// HumanVerificationMethods) are verified against protoncore (GPL-3.0)
// network/data/src/main/kotlin/me/proton/core/network/data/protonApi/BaseRetrofitApi.kt:61-64.

package app.alpensync.core.api.http

import app.alpensync.core.api.log.SafeLog
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Detects Proton's `Code: 9001` human-verification challenge and surfaces
 * it as a typed [HumanVerificationRequiredException]. We NEVER auto-retry
 * 9001 — it must reach the user so they can complete the captcha /
 * recovery-email / SMS flow (the in-app verify.proton.me sheet, falling
 * back to fail-closed manual instructions per ADR 0004 Q3 when the body
 * carries no usable `Details`).
 *
 * Implementation: peek the response body (size-capped) and parse the
 * top-level JSON `Code` field. Parsing tolerates whitespace and
 * field-order changes. For 9001 the `Details` block's
 * `HumanVerificationToken` + `HumanVerificationMethods` are extracted
 * independently and fail-closed: a missing/malformed `Details` (or one
 * field being garbage) yields nulls, never an exception, and never
 * suppresses the 9001 itself.
 *
 * `Code: 12087` ("CAPTCHA validation failed") means the stored HV token was
 * rejected — typically because HV tokens are single-use and the login retry
 * spends one token across two calls (`auth/info` then `auth`). Mirroring
 * protoncore's `HumanVerificationInvalidHandler`, the token is dropped and
 * the call replayed ONCE without it; the replay normally returns a fresh
 * 9001 whose `Details` open a NEW challenge. Before this, a 12087 surfaced
 * as a Details-less 9001 that the UI could not turn into a sheet, so a
 * correctly-solved challenge dead-ended on the manual-instructions screen.
 */
class HumanVerificationInterceptor(
    private val maxPeekBytes: Long = DEFAULT_MAX_PEEK_BYTES,
    private val tokens: HumanVerificationTokenSource = HumanVerificationTokenSource.Empty,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val parsed = peekHvCode(response) ?: return response
        response.close()
        if (parsed.code == STALE_CAPTCHA_CODE) return replayWithoutRejectedToken(chain)
        throw challengeFrom(chain.request(), parsed)
    }

    /**
     * Peeks the body (size-capped) for a 9001/12087 `Code`. Null means "not
     * a challenge" — a non-JSON body, an unreadable body, or any other Code.
     */
    private fun peekHvCode(response: Response): ParsedHv? {
        val contentType = response.body.contentType()?.subtype ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return null
        val snippet = try {
            response.peekBody(maxPeekBytes).string()
        } catch (ignored: IOException) {
            SafeLog.log(SafeLog.Event.RESPONSE_BODY_UNREADABLE)
            return null
        }
        return parseHvCode(snippet)
    }

    /**
     * Builds the typed 9001. A 9001 answering a request that already carried
     * a token means that token is stale — drop it so nothing loops it.
     */
    private fun challengeFrom(request: Request, parsed: ParsedHv): HumanVerificationRequiredException {
        if (request.header(HV_TOKEN_HEADER) != null) {
            tokens.clear()
            SafeLog.log(SafeLog.Event.HUMAN_VERIFICATION_STALE_TOKEN_CLEARED, parsed.code)
        } else {
            SafeLog.log(SafeLog.Event.HUMAN_VERIFICATION_REQUIRED, parsed.code)
        }
        return HumanVerificationRequiredException(
            verificationToken = parsed.token,
            verificationMethods = parsed.methods,
        )
    }

    /**
     * Code 12087 recovery (protoncore's `HumanVerificationInvalidHandler`):
     * clear the rejected token and replay the call once with the HV headers
     * stripped. Replaying at most once bounds the exchange — a second 12087
     * (the server rejecting a token we no longer send) falls back to the old
     * Details-less signal rather than looping.
     */
    private fun replayWithoutRejectedToken(chain: Interceptor.Chain): Response {
        tokens.clear()
        SafeLog.log(SafeLog.Event.HUMAN_VERIFICATION_STALE_TOKEN_CLEARED, STALE_CAPTCHA_CODE)
        val replayed = chain.proceed(
            chain.request().newBuilder()
                .removeHeader(HV_TOKEN_HEADER)
                .removeHeader(HV_TOKEN_TYPE_HEADER)
                .build(),
        )
        SafeLog.log(SafeLog.Event.HUMAN_VERIFICATION_TOKEN_REPLAYED, STALE_CAPTCHA_CODE)
        val parsed = peekHvCode(replayed) ?: return replayed
        replayed.close()
        if (parsed.code == STALE_CAPTCHA_CODE) throw HumanVerificationRequiredException()
        throw challengeFrom(replayed.request, parsed)
    }

    companion object {
        const val DEFAULT_MAX_PEEK_BYTES: Long = 8 * 1024
        const val HUMAN_VERIFICATION_CODE = 9001
        const val STALE_CAPTCHA_CODE = 12087
        const val HV_TOKEN_HEADER = "x-pm-human-verification-token"
        const val HV_TOKEN_TYPE_HEADER = "x-pm-human-verification-token-type"

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Returns non-null if [body] is a JSON object with `Code: 9001`
         * or `Code: 12087`. For 9001 the result carries the
         * `Details.HumanVerificationToken` / `.HumanVerificationMethods`
         * when extractable (each independently null on malformed input);
         * for 12087 they are always null.
         */
        internal fun parseHvCode(body: String): ParsedHv? = try {
            val root = lenientJson.parseToJsonElement(body).jsonObject
            when (val code = root["Code"]?.jsonPrimitive?.int) {
                HUMAN_VERIFICATION_CODE -> {
                    val details = detailsObject(body)
                    ParsedHv(code, tokenFrom(details), methodsFrom(details))
                }
                STALE_CAPTCHA_CODE -> ParsedHv(code, token = null, methods = null)
                else -> null
            }
        } catch (ignored: IllegalArgumentException) {
            null
        }

        /** The `Details` object, or null when absent or not a JSON object. */
        private fun detailsObject(body: String): JsonObject? = try {
            lenientJson.parseToJsonElement(body).jsonObject["Details"]?.jsonObject
        } catch (ignored: IllegalArgumentException) {
            null
        }

        /** Blank tokens are treated as absent — a blank header value is worse than none. */
        private fun tokenFrom(details: JsonObject?): String? = try {
            details?.get("HumanVerificationToken")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
        } catch (ignored: IllegalArgumentException) {
            null
        }

        /** Non-string entries are dropped; an empty/none list collapses to null. */
        private fun methodsFrom(details: JsonObject?): List<String>? = try {
            details?.get("HumanVerificationMethods")?.jsonArray
                ?.mapNotNull { element ->
                    runCatching { element.jsonPrimitive }.getOrNull()
                        ?.takeIf { it.isString }
                        ?.content
                }
                ?.takeIf { it.isNotEmpty() }
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }
}

internal data class ParsedHv(val code: Int, val token: String?, val methods: List<String>?)

/**
 * Thrown when Proton requires human verification (Code 9001, or a stale
 * captcha token via Code 12087). Callers MUST surface this to the user —
 * never auto-retry.
 *
 * [verificationToken] / [verificationMethods] carry the 9001 body's
 * `Details` block (protoncore-verified field names, see file header). When
 * either is null the caller cannot build the in-app verify.proton.me sheet
 * and shows manual verification instructions instead (ADR 0004 Q3
 * fail-closed).
 */
class HumanVerificationRequiredException(
    val verificationToken: String? = null,
    val verificationMethods: List<String>? = null,
    message: String = "Proton requires human verification (Code 9001)",
) : IOException(message)
