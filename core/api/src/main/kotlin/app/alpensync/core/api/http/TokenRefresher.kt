// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/http/TokenRefresher.kt
// Deviations:
//  - SafeLog instead of pcontacts' Logger.
//  - invalid-grant handling added (research notes Section 4): a 401/422 from
//    the refresh call itself fires [onSessionInvalid] so the caller wipes the
//    session and returns to login, instead of looping on a dead refresh token.
//  - catch clauses narrowed (detekt bans catching Exception/Throwable).

package app.alpensync.core.api.http

import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApi
import app.alpensync.core.api.dto.RefreshRequest
import app.alpensync.core.api.dto.RefreshResponse
import app.alpensync.core.api.log.SafeLog
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException

/**
 * Drives the `auth/refresh` half of the 401 → refresh → replay flow.
 * Single-flight: concurrent callers that observe the same stale access
 * token block on a lock; the first one through fires the actual refresh,
 * the rest return success once they see the session's token has rotated.
 *
 * [refreshOnlyApi] must be built on an OkHttpClient that is NOT wired
 * through [RefreshingAuthenticator] — a 401 on the refresh call itself
 * must never re-enter the authenticator (recursion guard).
 *
 * Failure semantics:
 *  - 9001 on refresh → the typed [HumanVerificationRequiredException]
 *    propagates; it is never demoted to a silent retry.
 *  - 401/422 from the refresh call → invalid grant: [onSessionInvalid]
 *    fires (caller wipes the session → return-to-login) and the refresh
 *    reports failure.
 *  - any other failure → report failure; the caller keeps the old session
 *    and surfaces a sync error.
 */
class TokenRefresher(
    private val refreshOnlyApi: ProtonApi,
    private val mutableSession: InMemorySession,
    private val getRefreshToken: () -> String?,
    private val onTokensRefreshed: (accessToken: String, refreshToken: String) -> Unit,
    private val onSessionInvalid: () -> Unit = {},
) {
    private val lock = ReentrantLock()

    /**
     * Refreshes the session if the access token the caller saw is still
     * the one the session is holding. Returns true when a usable fresh
     * token is available afterwards.
     *
     * @param tokenObservedDuring401 the bearer value present on the
     *        request that got the 401 — null if the request was already
     *        unauthenticated (unusual).
     */
    fun refreshIfStillStale(tokenObservedDuring401: String?): Boolean = lock.withLock {
        // Single-flight: if someone else already rotated the session token
        // while we were waiting on the lock, use their result.
        val nowToken = mutableSession.accessToken()
        if (nowToken != null && nowToken != tokenObservedDuring401) {
            return@withLock true
        }
        val refreshToken = getRefreshToken() ?: return@withLock false
        val response = callRefresh(refreshToken) ?: return@withLock false
        mutableSession.update(uid = response.uid, accessToken = response.accessToken)
        onTokensRefreshed(response.accessToken, response.refreshToken)
        true
    }

    /**
     * Fires the actual `auth/refresh` call. Returns null on failure — the
     * caller keeps the old session and surfaces a sync error, except for the
     * two documented terminal cases (9001 propagates typed; invalid grant
     * fires [onSessionInvalid] before returning null).
     */
    private fun callRefresh(refreshToken: String): RefreshResponse? = try {
        runBlocking { refreshOnlyApi.refresh(RefreshRequest(refreshToken = refreshToken)) }
    } catch (e: HumanVerificationRequiredException) {
        // 9001 on auth/refresh — propagate so the original caller sees
        // it; never demote to a silent retry or a spurious logout.
        SafeLog.log(SafeLog.Event.HUMAN_VERIFICATION_REQUIRED)
        throw e
    } catch (e: HttpException) {
        if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_UNPROCESSABLE) {
            // Invalid grant — the refresh token is dead. Wipe session.
            SafeLog.log(SafeLog.Event.TOKEN_REFRESH_INVALID_GRANT, e.code())
            onSessionInvalid()
        } else {
            SafeLog.log(SafeLog.Event.TOKEN_REFRESH_FAILED, e.code())
        }
        null
    } catch (ignored: IOException) {
        SafeLog.log(SafeLog.Event.TOKEN_REFRESH_FAILED)
        null
    } catch (ignored: IllegalArgumentException) {
        // Malformed refresh response body — fail closed, keep session.
        SafeLog.log(SafeLog.Event.TOKEN_REFRESH_FAILED)
        null
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_UNPROCESSABLE = 422
    }
}
