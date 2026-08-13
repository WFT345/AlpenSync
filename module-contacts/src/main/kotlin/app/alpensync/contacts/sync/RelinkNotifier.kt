package app.alpensync.contacts.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.alpensync.contacts.R
import app.alpensync.core.auth.store.DisconnectNoticeStore

/**
 * Tells the user Proton dropped the session while they were not looking.
 * Posts only if the OS allows it; the persisted disconnect flag still
 * drives the in-app Relink screen either way.
 */
object RelinkNotifier {

    const val CHANNEL_ID: String = "alpensync.account"
    const val NOTIFICATION_ID: Int = 41

    fun notifyDisconnected(context: Context) {
        if (!canPost(context)) return
        val manager = manager(context) ?: return
        ensureChannel(manager, context)
        manager.notify(NOTIFICATION_ID, build(context))
    }

    /** After reboot or a swipe-away: put it back if they still need to relink. */
    fun repostIfNeeded(context: Context) {
        if (DisconnectNoticeStore(context).reason() == null) return
        notifyDisconnected(context)
    }

    fun cancel(context: Context) {
        manager(context)?.cancel(NOTIFICATION_ID)
    }

    private fun canPost(context: Context): Boolean {
        if (!RelinkAlertPrefs(context).optedIn()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(manager: NotificationManager, context: Context) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.relink_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.relink_channel_desc) },
        )
    }

    private fun build(context: Context): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relink)
            .setContentTitle(context.getString(R.string.relink_notification_title))
            .setContentText(context.getString(R.string.relink_notification_body))
            .setContentIntent(pending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
    }
}
