// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/storage/src/main/kotlin/io/pcontacts/core/storage/EncryptedSecretStore.kt
// Deviation: per-account storage (plan Section 5.5) — the prefs file and the
// KEK alias are both suffixed with the account id; account ids are sanitized
// so a hostile value can never escape the file/alias namespace.

package app.alpensync.core.auth.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64

/**
 * Production [SecretStore] (ADR 0004 Section 3), one instance per account:
 *   - Tokens (UID, AccessToken, RefreshToken) and the human-verification
 *     token land in EncryptedSharedPreferences (AES256_SIV keys /
 *     AES256_GCM values, StrongBox-requested MasterKey).
 *   - The mailbox keyPassword is double-wrapped: [KeystoreAesGcmKek]
 *     (per-account alias) encrypts the bytes; the blob is base64-encoded
 *     and stored alongside the tokens.
 *   - [logout] deletes the prefs entries AND the KEK alias.
 */
class EncryptedSecretStore private constructor(
    private val prefs: SharedPreferences,
    private val kek: KeystoreAesGcmKek,
) : SecretStore {

    override fun uid(): String? = prefs.getString(KEY_UID, null)
    override fun setUid(value: String?) = prefs.put(KEY_UID, value)

    override fun accessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    override fun setAccessToken(value: String?) = prefs.put(KEY_ACCESS_TOKEN, value)

    override fun refreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    override fun setRefreshToken(value: String?) = prefs.put(KEY_REFRESH_TOKEN, value)

    override fun keyPassword(): ByteArray? {
        val wrappedB64 = prefs.getString(KEY_PASSWORD_WRAPPED, null) ?: return null
        return kek.unwrap(Base64.getDecoder().decode(wrappedB64))
    }

    override fun setKeyPassword(value: ByteArray?) {
        if (value == null) {
            prefs.put(KEY_PASSWORD_WRAPPED, null)
            return
        }
        val wrapped = kek.wrap(value)
        prefs.put(KEY_PASSWORD_WRAPPED, Base64.getEncoder().encodeToString(wrapped))
    }

    override fun humanVerificationToken(): String? = prefs.getString(KEY_HV_TOKEN, null)
    override fun setHumanVerificationToken(value: String?) = prefs.put(KEY_HV_TOKEN, value)

    override fun humanVerificationTokenType(): String? = prefs.getString(KEY_HV_TOKEN_TYPE, null)
    override fun setHumanVerificationTokenType(value: String?) = prefs.put(KEY_HV_TOKEN_TYPE, value)

    override fun logout() {
        prefs.edit()
            .remove(KEY_UID)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_PASSWORD_WRAPPED)
            .remove(KEY_HV_TOKEN)
            .remove(KEY_HV_TOKEN_TYPE)
            .apply()
        kek.delete()
    }

    private fun SharedPreferences.put(key: String, value: String?) {
        edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        private const val FILE_PREFIX: String = "alpensync_auth_"
        private const val KEK_ALIAS_PREFIX: String = "alpensync.kek."
        private const val KEY_UID: String = "uid"
        private const val KEY_ACCESS_TOKEN: String = "access_token"
        private const val KEY_REFRESH_TOKEN: String = "refresh_token"
        private const val KEY_PASSWORD_WRAPPED: String = "key_password_wrapped"
        private const val KEY_HV_TOKEN: String = "hv_token"
        private const val KEY_HV_TOKEN_TYPE: String = "hv_token_type"

        /**
         * Creates (or opens) the store for [accountId]. Construction is a
         * single side-effect-free factory call so the encrypted-prefs init
         * (which touches Keystore) runs at a predictable lifecycle point.
         */
        fun create(context: Context, accountId: String): EncryptedSecretStore {
            val safeAccount = sanitize(accountId)
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_PREFIX + safeAccount,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedSecretStore(prefs, KeystoreAesGcmKek(KEK_ALIAS_PREFIX + safeAccount))
        }

        /** Keep the account id safe for file names and Keystore aliases. */
        private fun sanitize(accountId: String): String {
            val cleaned = accountId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            require(cleaned.isNotEmpty()) { "accountId must contain at least one safe character" }
            return cleaned
        }
    }
}
