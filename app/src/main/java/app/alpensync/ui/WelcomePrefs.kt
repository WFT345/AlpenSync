package app.alpensync.ui

import android.content.Context

/** First-open welcome. Not secrets. */
class WelcomePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seen(): Boolean = prefs.getBoolean(KEY, false)

    fun markSeen() {
        prefs.edit().putBoolean(KEY, true).apply()
    }

    companion object {
        private const val PREFS = "alpensync.welcome"
        private const val KEY = "seen"

        fun shouldShow(context: Context, hasRelinkNotice: Boolean): Boolean =
            shouldShowWelcome(seen = WelcomePrefs(context).seen(), hasRelinkNotice = hasRelinkNotice)
    }
}

fun shouldShowWelcome(seen: Boolean, hasRelinkNotice: Boolean): Boolean {
    if (hasRelinkNotice) return false
    return !seen
}
