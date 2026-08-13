package app.alpensync.contacts.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.alpensync.core.auth.store.DisconnectNoticeStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelinkNotifierTest {

    @Test
    fun posts_and_cancels_the_relink_notification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RelinkAlertPrefs(context).accept()
        val manager = context.getSystemService(NotificationManager::class.java)
        RelinkNotifier.notifyDisconnected(context)
        assertEquals(1, manager.activeNotifications.size)
        val posted = manager.activeNotifications[0]
        assertEquals(RelinkNotifier.NOTIFICATION_ID, posted.id)
        assertEquals(
            "Relink Proton",
            posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(Notification.FLAG_ONGOING_EVENT, posted.notification.flags and Notification.FLAG_ONGOING_EVENT)
        RelinkNotifier.cancel(context)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun repost_does_nothing_until_a_disconnect_is_recorded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        RelinkNotifier.repostIfNeeded(context)
        assertEquals(0, manager.activeNotifications.size)
        RelinkAlertPrefs(context).accept()
        DisconnectNoticeStore(context).markRevoked()
        RelinkNotifier.repostIfNeeded(context)
        assertEquals(1, manager.activeNotifications.size)
        RelinkNotifier.cancel(context)
        DisconnectNoticeStore(context).clear()
    }
}
