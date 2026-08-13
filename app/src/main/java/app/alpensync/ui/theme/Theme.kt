package app.alpensync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AlpenColors = darkColorScheme(
    primary = AlpenFg,
    onPrimary = AlpenBg,
    secondary = AlpenIce,
    onSecondary = AlpenBg,
    background = AlpenBg,
    onBackground = AlpenFg,
    surface = AlpenBg,
    onSurface = AlpenFg,
    surfaceVariant = AlpenBg2,
    onSurfaceVariant = AlpenMute,
    outline = AlpenRule,
    error = AlpenError,
    onError = AlpenBg,
)

@Composable
fun AlpenSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AlpenColors,
        typography = AlpenTypography,
        content = content,
    )
}
