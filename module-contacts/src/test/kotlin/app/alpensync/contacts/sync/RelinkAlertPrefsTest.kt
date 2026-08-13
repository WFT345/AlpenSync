package app.alpensync.contacts.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelinkAlertPrefsTest {

    @Test
    fun first_launch_shows_the_primer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("alpensync.disconnect", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(RelinkAlertPrefs.shouldShowPrimer(context))
    }

    @Test
    fun decline_hides_primer_and_blocks_notify() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("alpensync.disconnect", Context.MODE_PRIVATE).edit().clear().commit()
        RelinkAlertPrefs(context).decline()
        assertFalse(RelinkAlertPrefs.shouldShowPrimer(context))
        RelinkNotifier.notifyDisconnected(context)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
