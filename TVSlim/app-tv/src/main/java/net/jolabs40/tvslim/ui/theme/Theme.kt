package net.jolabs40.tvslim.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Dark palette: anything brighter dazzles in a dark room. */
private val darkScheme = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    onPrimary = Color(0xFF05281B),
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF0A2540),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6E9EC),
    surface = Color(0xFF1A1F25),
    onSurface = Color(0xFFE6E9EC),
    surfaceVariant = Color(0xFF262C33),
    onSurfaceVariant = Color(0xFFB9C0C7),
    error = Color(0xFFF2837F),
    onError = Color(0xFF3B0907),
)

/** Risk dot colors, outside the Material palette so they read at a glance. */
object RiskColors {
    val none = Color(0xFF7FD1AE)
    val low = Color(0xFFD8E07F)
    val medium = Color(0xFFE0B57F)
    val high = Color(0xFFF2837F)
}

@Composable
fun TvSlimTheme(content: @Composable () -> Unit) {
    // Outside a Surface, `Text` uses the default `LocalContentColor`, which is dark, so titles would be
    // black on black. Surfaces provide their own further down and are unaffected.
    MaterialTheme(colorScheme = darkScheme) {
        CompositionLocalProvider(
            LocalContentColor provides darkScheme.onBackground,
            content = content,
        )
    }
}
