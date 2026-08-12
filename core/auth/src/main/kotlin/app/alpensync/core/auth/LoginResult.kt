// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/sync/src/main/kotlin/io/pcontacts/core/sync/auth/LoginResult.kt
// Deviation: added TwoPasswordUnsupported — ADR 0004 Q2 (owner decision):
// PasswordMode==2 accounts fail loud instead of silently mis-deriving keys.

package app.alpensync.core.auth

import app.alpensync.core.api.http.EndpointFamily

/**
 * Outcome of an SRP login attempt. `Failed.reason` is a short,
 * non-sensitive string — never a server message or credential material.
 */
sealed interface LoginResult {
    val uid: String?
    val username: String?

    data class Success(override val uid: String, override val username: String) : LoginResult

    data class TwoFactorRequired(override val uid: String, override val username: String) : LoginResult

    /**
     * Proton returned Code:9001 during login. The user must complete a
     * captcha (or recovery-email/SMS challenge) before SRP can succeed;
     * afterwards the caller re-invokes `login(...)` with the same
     * credentials and the stored HV token headers do the rest.
     *
     * [verificationToken] / [verificationMethods] carry the 9001 body's
     * `Details` block (field names verified against protoncore's
     * BaseRetrofitApi.kt:61-64; mechanics in
     * docs/research/m1-auth-api-notes.md Section 1). When either is null
     * the in-app verify.proton.me sheet cannot be built and the caller
     * shows manual verification instructions (ADR 0004 Q3 fail-closed).
     */
    data class HumanVerificationRequired(
        val verificationToken: String?,
        val verificationMethods: List<String>?,
        override val uid: String? = null,
        override val username: String? = null,
    ) : LoginResult

    /**
     * The account uses Proton's two-password mode (PasswordMode == 2: the
     * mailbox password differs from the login password). Key derivation
     * would silently fail, so login aborts loudly here instead (ADR 0004
     * Q2). Any partially-created session is wiped before this is returned.
     */
    data class TwoPasswordUnsupported(
        override val uid: String? = null,
        override val username: String? = null,
    ) : LoginResult

    /**
     * The server answered with an HTTP error whose `Code` has no typed path
     * in this build (first seen in live test 1: a post-captcha 422 on
     * `auth`). [protonCode] is the body's integer `Code` (null when the
     * body carried none); [endpointFamily] the failing call stage — both
     * non-secret and shown to the user verbatim as "code N at <stage>".
     */
    data class ServerError(
        val protonCode: Int?,
        val endpointFamily: EndpointFamily,
        override val uid: String? = null,
        override val username: String? = null,
    ) : LoginResult

    data class Failed(
        val reason: String,
        override val uid: String? = null,
        override val username: String? = null,
    ) : LoginResult
}
