package app.terbaca.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = TerbacaDarkAccent,
    onPrimary = TerbacaDarkBackground,
    background = TerbacaDarkBackground,
    onBackground = TerbacaDarkTextPrimary,
    surface = TerbacaDarkSurface,
    onSurface = TerbacaDarkTextPrimary,
    surfaceVariant = TerbacaDarkSurfaceVariant,
    onSurfaceVariant = TerbacaDarkTextSecondary,
    outline = TerbacaDarkBorder
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
