package app.terbaca.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = TerbacaDarkAccent,
    onPrimary = Color.White,
    primaryContainer = TerbacaDarkAccent,
    onPrimaryContainer = Color.White,
    secondaryContainer = TerbacaDarkSurfaceVariant,
    onSecondaryContainer = TerbacaDarkTextPrimary,
    background = TerbacaDarkBackground,
    onBackground = TerbacaDarkTextPrimary,
    surface = TerbacaDarkSurface,
    onSurface = TerbacaDarkTextPrimary,
    surfaceVariant = TerbacaDarkSurfaceVariant,
    onSurfaceVariant = TerbacaDarkTextSecondary,
    surfaceContainerLowest = TerbacaDarkBackground,
    surfaceContainerLow = TerbacaDarkSurface,
    surfaceContainer = TerbacaDarkSurface,
    surfaceContainerHigh = TerbacaDarkSurfaceVariant,
    surfaceContainerHighest = TerbacaDarkSurfaceHigh,
    outline = TerbacaDarkBorder,
    outlineVariant = TerbacaDarkBorder
)

@Composable
fun TerbacaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
