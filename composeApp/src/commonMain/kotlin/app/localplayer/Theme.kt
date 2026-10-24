package app.localplayer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB7D66D),
    onPrimary = Color(0xFF1D2606),
    primaryContainer = Color(0xFF394F1D),
    onPrimaryContainer = Color(0xFFE5F5B5),
    secondary = Color(0xFF9CC7AA),
    onSecondary = Color(0xFF0D2319),
    secondaryContainer = Color(0xFF254A3C),
    onSecondaryContainer = Color(0xFFD3F4E6),
    tertiary = Color(0xFFD7B36A),
    onTertiary = Color(0xFF2A1E00),
    background = Color(0xFF0F1210),
    onBackground = Color(0xFFEAF2ED),
    surface = Color(0xFF121813),
    onSurface = Color(0xFFEAF2ED),
    surfaceVariant = Color(0xFF2A352F),
    onSurfaceVariant = Color(0xFFC6D1C8),
    outline = Color(0xFF6C7B72),
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B241F),
    surfaceContainerHigh = Color(0xFF252F2B),
    surfaceContainerHighest = Color(0xFF2F3935),
    inverseSurface = Color(0xFFEAF2ED),
    inverseOnSurface = Color(0xFF1A201D),
)

private val AmoledScheme = DarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF090D0A),
    surfaceContainer = Color(0xFF0E1210),
    surfaceContainerHigh = Color(0xFF121916),
    surfaceContainerHighest = Color(0xFF18211D),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5E7E3B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBC1),
    onPrimaryContainer = Color(0xFF1D2F09),
    secondary = Color(0xFF507A6A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8F1E5),
    onSecondaryContainer = Color(0xFF102A1F),
    tertiary = Color(0xFF9C7346),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFEEF0E7),
    onBackground = Color(0xFF1A1A17),
    surface = Color(0xFFF7F8F3),
    onSurface = Color(0xFF1A1A17),
    surfaceVariant = Color(0xFFE5E7E1),
    onSurfaceVariant = Color(0xFF4F514B),
    outline = Color(0xFF7A7F74),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3EE),
    surfaceContainer = Color(0xFFE9EDE3),
    surfaceContainerHigh = Color(0xFFE2E6DD),
    surfaceContainerHighest = Color(0xFFD9DDD4),
    inverseSurface = Color(0xFF1E201D),
    inverseOnSurface = Color(0xFFF4F4F0),
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
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
