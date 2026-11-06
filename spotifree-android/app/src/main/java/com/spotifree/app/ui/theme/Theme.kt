package com.spotifree.app.ui.theme

import com.spotifree.app.util.runCatchingCancellable

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spotifree.app.R

/** Extra colours the screens need besides Material's (dark theme only, like the previous app). */
@Immutable
data class SpotifreeColors(
    val accent: Color,
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val textSecondary: Color,
    val amoled: Boolean,
    val textMuted: Color = Color(0xFF727272),
    val chip: Color = Color(0x1AFFFFFF),
)

val LocalSpotifreeColors = staticCompositionLocalOf {
    SpotifreeColors(Color(0xFF2979FF), Color(0xFF121212), Color(0xFF181818), Color(0xFF282828), Color(0xFFB3B3B3), false)
}

/** Plus Jakarta Sans: the typeface of the previous app (SIL Open Font License). */
val JakartaSans = FontFamily(
    Font(R.font.plus_jakarta_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_extrabold, FontWeight.ExtraBold),
    Font(R.font.plus_jakarta_extrabold, FontWeight.Black),
)

/** Spotify's "Liked Songs" gradient. */
val LikedGradient = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5))

val ACCENT_PRESETS = listOf("#2979ff", "#1db954", "#e91e63", "#ff9800", "#9c27b0", "#00bcd4", "#f44336", "#ffeb3b")

fun parseColor(hex: String, fallback: Color = Color(0xFF2979FF)): Color = runCatchingCancellable {
    val h = hex.removePrefix("#")
    val v = h.toLong(16)
    when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> fallback
    }
}.getOrDefault(fallback)

/** Readable text colour on top of [c]. */
fun onColor(c: Color): Color {
    val l = 0.299 * c.red + 0.587 * c.green + 0.114 * c.blue
    return if (l > 0.6) Color.Black else Color.White
}

@Composable
fun SpotifreeTheme(accentHex: String, amoled: Boolean, content: @Composable () -> Unit) {
    val accent = parseColor(accentHex)
    // AMOLED: pure black backgrounds (pixels off) with slightly lifted cards so they stay visible.
    val bg = if (amoled) Color.Black else Color(0xFF121212)
    val surface = if (amoled) Color(0xFF0A0A0A) else Color(0xFF181818)
    val elevated = if (amoled) Color(0xFF161616) else Color(0xFF282828)
    val colors = SpotifreeColors(accent, bg, surface, elevated, Color(0xFFB3B3B3), amoled)
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = onColor(accent),
        secondary = accent,
        onSecondary = onColor(accent),
        background = bg,
        onBackground = Color.White,
        surface = bg,
        onSurface = Color.White,
        surfaceVariant = elevated,
        onSurfaceVariant = Color(0xFFB3B3B3),
        surfaceContainer = surface,
        surfaceContainerLow = surface,
        surfaceContainerLowest = bg,
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = if (amoled) Color(0xFF1E1E1E) else Color(0xFF333333),
        outline = Color(0xFF535353),
        outlineVariant = Color(0xFF3E3E3E),
        error = Color(0xFFFF5252),
    )
    val typography = spotifreeTypography()
    androidx.compose.runtime.CompositionLocalProvider(LocalSpotifreeColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // Text outside a Surface (overlays, sheets' custom rows) must default to white on dark
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White, content = content)
        }
    }
}

/** Material type scale on Plus Jakarta Sans, with the tight, heavy headings of the previous app. */
private fun spotifreeTypography(): Typography {
    val base = Typography()
    fun TextStyle.j(weight: FontWeight? = null, spacing: Float = -0.01f) =
        copy(fontFamily = JakartaSans, fontWeight = weight ?: fontWeight, letterSpacing = spacing.em)
    return Typography(
        displayLarge = base.displayLarge.j(FontWeight.ExtraBold, -0.03f),
        displayMedium = base.displayMedium.j(FontWeight.ExtraBold, -0.03f),
        displaySmall = base.displaySmall.j(FontWeight.ExtraBold, -0.03f),
        headlineLarge = base.headlineLarge.j(FontWeight.ExtraBold, -0.03f),
        headlineMedium = base.headlineMedium.j(FontWeight.ExtraBold, -0.03f),
        headlineSmall = base.headlineSmall.copy(fontSize = 24.sp).j(FontWeight.ExtraBold, -0.03f),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp).j(FontWeight.ExtraBold, -0.02f),
        titleMedium = base.titleMedium.j(FontWeight.Bold),
        titleSmall = base.titleSmall.j(FontWeight.Bold),
        bodyLarge = base.bodyLarge.j(),
        bodyMedium = base.bodyMedium.j(),
        bodySmall = base.bodySmall.j(),
        labelLarge = base.labelLarge.j(FontWeight.Bold),
        labelMedium = base.labelMedium.j(FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold).j(spacing = 0f),
    )
}
