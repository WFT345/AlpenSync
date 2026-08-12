package app.alpensync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Minimal M3 theme for the M0 shell. Brand colors are deliberately not
// invented yet; defaults keep the UI honest and unstyled until the
// onboarding/settings UI is designed at M4.
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun AlpenSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
