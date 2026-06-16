package ai.tournesol.pureprivacy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PurePrivacy "calm sovereignty" — dark-first, sunflower accent. The neutrals are the
// desktop's warm "candlelight" palette so the two clients read as one brand: warm near-
// black backgrounds, warm panels, warm paper/dim text (no cool blue-grey).
val Sunflower = Color(0xFFF2B705)
val SunflowerDim = Color(0xFFB8860B)
val Ink = Color(0xFF16140F)        // desktop --bg
val InkSoft = Color(0xFF1D1A12)    // desktop panel
val InkCard = Color(0xFF221F17)    // desktop raised panel
val Paper = Color(0xFFECE6D7)      // desktop paper
val PaperDim = Color(0xFF9A9384)   // desktop dim
val BubbleMine = Color(0xFF2D2A1F)     // warm-tinted "mine" bubble (sits above InkCard)
val BubbleTheirs = Color(0xFF221F17)   // = InkCard

// Semantic status tokens (adopting the desktop values). Use these instead of scattering
// raw hex: Danger for destructive/error, Success for the secure "you are protected"
// state, Divider for hairline separators. Gold (Sunflower) is reserved for ACTIONABLE
// controls — so a steady-state "secure" signal reads as Success, not as a call to act.
val Danger = Color(0xFFE06650)
val Success = Color(0xFF4CAF7A)
val Divider = Color(0xFF1B222B)
val Outline = Color(0xFF2D3742)

private val PpColors = darkColorScheme(
    primary = Sunflower,
    onPrimary = Ink,
    secondary = SunflowerDim,
    background = Ink,
    onBackground = Paper,
    surface = InkSoft,
    onSurface = Paper,
    surfaceVariant = InkCard,
    onSurfaceVariant = PaperDim,
    outline = Outline,
    error = Danger,
)

@Composable
fun PurePrivacyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PpColors, content = content)
}
