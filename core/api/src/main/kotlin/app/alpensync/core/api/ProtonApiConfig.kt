// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/ProtonApiConfig.kt

package app.alpensync.core.api

/**
 * The single patch point for Proton API access (plan Section 10.4, ADR 0004
 * Section 2): base URL, the `x-pm-appversion` contract selector, optional
 * locale.
 *
 * `x-pm-appversion` is a client identifier that selects a server-side API
 * *contract* — it is NOT "the latest app version". Live-verified by
 * pcontacts against `POST core/v4/auth/info` (2026-07-28): the direct
 * auth/info SRP flow is accepted for `android-mail@2.0.0` through
 * `android-mail@3.0.12`; `1.0.0` gets Code 5003 (force upgrade), `3.0.13+`
 * (incl. the current 7.x line) gets 401 because it requires an
 * unauthenticated-session token we do not implement. Do NOT bump this to
 * the latest official android-mail release.
 *
 * When the pinned version ages out, Proton answers with JSON `Code` 5003
 * or 5004 — [app.alpensync.core.api.http.AppVersionRejectionInterceptor]
 * turns that into a typed fail-loud error.
 */
data class ProtonApiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val appVersion: String = "android-mail@$DEFAULT_APP_VERSION",
    val locale: String? = null,
) {
    init {
        require(baseUrl.endsWith("/")) { "baseUrl must end with '/'" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://mail-api.proton.me/"
        const val DEFAULT_APP_VERSION: String = "3.0.12"
        const val ACCEPT_HEADER: String = "application/vnd.protonmail.v1+json"
    }
}
