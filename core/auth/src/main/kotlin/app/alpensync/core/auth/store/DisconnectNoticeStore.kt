package app.alpensync.core.auth.store

import android.content.Context

/**
 * Non-secret flag: Proton dropped the session (or the stored session was
 * unusable). Survives [SecretStore.logout] so the next UI open can say
 * "relink" instead of a blank login. Never holds tokens or identities.
 */
class DisconnectNoticeStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reason(): String? = prefs.getString(KEY, null)

    fun markRevoked() = prefs.edit().putString(KEY, REASON_REVOKED).apply()

    fun markIncomplete() = prefs.edit().putString(KEY, REASON_INCOMPLETE).apply()

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        const val REASON_REVOKED: String = "session_revoked"
        const val REASON_INCOMPLETE: String = "session_incomplete"
        private const val PREFS: String = "alpensync.disconnect"
        private const val KEY: String = "reason"
    }
}
