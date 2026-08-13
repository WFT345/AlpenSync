package app.alpensync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.alpensync.contacts.sync.RelinkNotifier

/** After a reboot the ongoing notice is gone; put it back until they relink. */
class RelinkBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        RelinkNotifier.repostIfNeeded(context)
    }
}
