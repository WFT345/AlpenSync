// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/HumanVerificationTokenSource.kt

package app.alpensync.core.api.http

/**
 * Read/clear surface for the captcha verification token that Proton's
 * `x-pm-human-verification-token` + `x-pm-human-verification-token-type`
 * headers carry. Kept as an interface so `:core:api` doesn't depend on
 * `:core:auth`'s storage (the production implementation wraps the
 * SecretStore; ADR 0004 Q4 — the token lives in the encrypted prefs).
 *
 * Header names verified against protoncore's
 * `network/data/.../ProtonApiBackend.kt::prepareHeaders`.
 */
interface HumanVerificationTokenSource {

    /** Opaque verification token, or null when no captcha has been solved. */
    fun token(): String?

    /** Token type — `"captcha"`, `"email"`, `"sms"`, `"payment"`, etc. */
    fun tokenType(): String?

    /**
     * Clears the stored token. Called by [HumanVerificationInterceptor]
     * when a 9001 fires on a request that already carried the headers
     * (stale-token recovery), and on logout.
     */
    fun clear()

    /** No-op source — tests and the refresh-only OkHttpClient. */
    object Empty : HumanVerificationTokenSource {
        override fun token(): String? = null
        override fun tokenType(): String? = null
        override fun clear() = Unit
    }
}
