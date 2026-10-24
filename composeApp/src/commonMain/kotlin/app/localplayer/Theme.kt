package app.localplayer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Approximate olive + gold palette inspired by PixelPlayer (not sampled from its screenshots).
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE3C565),
    onPrimary = Color(0xFF3A2F00),
    primaryContainer = Color(0xFF54460A),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFFA9CF86),
    onSecondary = Color(0xFF193807),
    secondaryContainer = Color(0xFF2F4F1B),
    onSecondaryContainer = Color(0xFFC4EBA0),
    tertiary = Color(0xFFE8B98A),
    background = Color(0xFF14160E),
    onBackground = Color(0xFFE5E4D5),
    surface = Color(0xFF14160E),
    onSurface = Color(0xFFE5E4D5),
    surfaceVariant = Color(0xFF46483A),
    onSurfaceVariant = Color(0xFFC9C9B6),
    outline = Color(0xFF929280),
    surfaceContainerLow = Color(0xFF1B1D14),
    surfaceContainer = Color(0xFF202218),
    surfaceContainerHigh = Color(0xFF2A2C21),
    surfaceContainerHighest = Color(0xFF35372B),
)

private val AmoledScheme = DarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0B07),
    surfaceContainer = Color(0xFF11120C),
    surfaceContainerHigh = Color(0xFF181A12),
    surfaceContainerHighest = Color(0xFF20221A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D5E0F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF8E287),
    onPrimaryContainer = Color(0xFF211B00),
    secondary = Color(0xFF4F6528),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1EBA2),
    onSecondaryContainer = Color(0xFF0E2000),
    tertiary = Color(0xFF7B5733),
    background = Color(0xFFFEF9EC),
    onBackground = Color(0xFF1D1C14),
    surface = Color(0xFFFEF9EC),
    onSurface = Color(0xFF1D1C14),
    surfaceVariant = Color(0xFFE9E2D0),
    onSurfaceVariant = Color(0xFF4A4739),
    outline = Color(0xFF7B7768),
    surfaceContainerLow = Color(0xFFF8F3E5),
    surfaceContainer = Color(0xFFF2EDDF),
    surfaceContainerHigh = Color(0xFFECE7D9),
    surfaceContainerHighest = Color(0xFFE6E2D4),
)

@Composable
fun LocalPlayerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        else -> true
    }
    val scheme = when {
        mode == ThemeMode.AMOLED -> AmoledScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    // Default typography on iOS is the system font.
    MaterialTheme(colorScheme = scheme, content = content)
}
