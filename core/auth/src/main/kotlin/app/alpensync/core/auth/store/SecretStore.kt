// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/src/main/kotlin/io/pcontacts/core/storage/SecretStore.kt
// Deviation: storage is per-account (plan Section 5.5 multi-account data
// model) — one store instance per accountId, enforced by the factory.

package app.alpensync.core.auth.store

/**
 * Single read/write surface for every sensitive value the app holds for
 * one Proton account: session UID, AccessToken, RefreshToken, the
 * human-verification token, and the mailbox keyPassword.
 *
 * Per ADR 0004 Section 3:
 *   - This is the ONLY place in the codebase that touches
 *     `SharedPreferences` and the Android Keystore for secrets.
 *   - The mailbox keyPassword is wrapped under a Keystore-backed
 *     AES-256-GCM key (per-account alias) before it touches
 *     EncryptedSharedPreferences. The wrap key is deleted on logout.
 *   - On [logout] every field is wiped and the Keystore alias is deleted.
 */
interface SecretStore {

    fun uid(): String?
    fun setUid(value: String?)

    fun accessToken(): String?
    fun setAccessToken(value: String?)

    fun refreshToken(): String?
    fun setRefreshToken(value: String?)

    /** Returns the unwrapped keyPassword bytes, or null if not stored. */
    fun keyPassword(): ByteArray?

    /** Wraps and stores the keyPassword bytes under the Keystore AEAD key. */
    fun setKeyPassword(value: ByteArray?)

    /**
     * Human-verification token (`x-pm-human-verification-token`) and its
     * type (`x-pm-human-verification-token-type`, e.g. `"captcha"`),
     * persisted in the encrypted prefs (ADR 0004 Q4) so subsequent
     * requests can attach the two headers transparently. Cleared by
     * [logout] and by the HTTP layer on a stale-token 9001/12087.
     */
    fun humanVerificationToken(): String?
    fun setHumanVerificationToken(value: String?)
    fun humanVerificationTokenType(): String?
    fun setHumanVerificationTokenType(value: String?)

    /**
     * Wipes every secret and deletes the Keystore AEAD key for this
     * account. Subsequent reads return null.
     */
    fun logout()
}
