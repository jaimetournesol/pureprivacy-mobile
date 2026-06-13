package ai.tournesol.pureprivacy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PurePrivacy "calm sovereignty" — dark-first, sunflower accent.
val Sunflower = Color(0xFFF2B705)
val SunflowerDim = Color(0xFFB8860B)
val Ink = Color(0xFF0E1116)
val InkSoft = Color(0xFF161B22)
val InkCard = Color(0xFF1C232D)
val Paper = Color(0xFFE6EDF3)
val PaperDim = Color(0xFF8B98A5)
val BubbleMine = Color(0xFF2A3340)
val BubbleTheirs = Color(0xFF1C232D)

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
    outline = Color(0xFF2D3742),
)

@Composable
fun PurePrivacyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PpColors, content = content)
}
