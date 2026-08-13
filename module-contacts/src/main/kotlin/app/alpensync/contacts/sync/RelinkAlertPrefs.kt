package app.alpensync.contacts.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Primer + opt-in for the relink notification. Not secrets. */
class RelinkAlertPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun primerDone(): Boolean = prefs.getBoolean(KEY_PRIMER, false)

    fun optedIn(): Boolean = prefs.getBoolean(KEY_OPTED_IN, false)

    fun accept() {
        prefs.edit().putBoolean(KEY_PRIMER, true).putBoolean(KEY_OPTED_IN, true).apply()
    }

    fun decline() {
        prefs.edit().putBoolean(KEY_PRIMER, true).putBoolean(KEY_OPTED_IN, false).apply()
    }

    companion object {
        private const val PREFS = "alpensync.disconnect"
        private const val KEY_PRIMER = "primer_done"
        private const val KEY_OPTED_IN = "alerts_opted_in"

        fun shouldShowPrimer(context: Context): Boolean {
            val store = RelinkAlertPrefs(context)
            if (store.primerDone()) return false
            if (alreadyGranted(context)) {
                store.accept()
                return false
            }
            return true
        }

        fun alreadyGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 33) return false
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
