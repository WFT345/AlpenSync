package app.alpensync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import app.alpensync.ui.AppScreen
import app.alpensync.ui.theme.AlpenBg
import app.alpensync.ui.theme.AlpenSyncTheme

/**
 * Single-activity shell. [LoginController] does all core wiring; no
 * ViewModel/DI ceremony (plan Rule 14). A fresh controller is created per
 * activity instance — the persisted session makes that transparent.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = AlpenBg.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(bar),
            navigationBarStyle = SystemBarStyle.dark(bar),
        )
        val controller = LoginController(applicationContext)
        val syncController = SyncDebugController(applicationContext)
        setContent {
            AlpenSyncTheme {
                AppScreen(controller, syncController)
            }
        }
    }
}
