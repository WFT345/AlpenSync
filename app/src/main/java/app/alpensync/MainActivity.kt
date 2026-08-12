package app.alpensync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.alpensync.ui.theme.AlpenSyncTheme

/**
 * Single-activity shell hosting the M1 debug login screen (plan Section 6
 * acceptance). [LoginController] does all core wiring; no ViewModel/DI
 * ceremony (plan Rule 14). A fresh controller is created per activity
 * instance — the persisted session makes that transparent: a relaunch with
 * stored tokens restores straight into the LoggedIn state.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = LoginController(applicationContext)
        // M2d: the sync debug controller (account + scheduler + Sync now).
        val syncController = SyncDebugController(applicationContext)
        setContent {
            AlpenSyncTheme {
                DebugLoginScreen(controller, syncController)
            }
        }
    }
}
