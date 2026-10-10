package app.terbaca.core.ui.theme

import androidx.compose.ui.graphics.Color

// Dark palette: the background and text from docs/decisions.md, the rest after Apple's dark
// system colours, so the chrome reads like Books and Preview.
val TerbacaDarkBackground = Color(0xFF121214)
val TerbacaDarkSurface = Color(0xFF1C1C1E)
val TerbacaDarkSurfaceVariant = Color(0xFF2C2C2E)
val TerbacaDarkSurfaceHigh = Color(0xFF3A3A3C)
val TerbacaDarkTextPrimary = Color(0xFFE4E4E7)
val TerbacaDarkTextSecondary = Color(0xFF8E8E93)
val TerbacaDarkAccent = Color(0xFF0A84FF)
val TerbacaDarkBorder = Color(0xFF38383A)

/**
 * Toolbars and floating pills. Opaque: without a backdrop blur (iOS has one, Android doesn't),
 * page text ghosting through a see-through bar looks messy.
 */
val TerbacaChrome = Color(0xFF1C1C1E)
