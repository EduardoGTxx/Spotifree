package com.spotifree.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    val isGlassy: Boolean = false,
    val glassBorder: Color = Color.Transparent,
    val glassHighlight: Color = Color.Transparent,
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

/** Opaque gradient colors blending [accent] over base #090B14 for the Glassy theme. */
fun glassyGradient(accent: Color): List<Color> {
    val baseBg = Color(0xFF090B14)
    val topOpaque = Color(
        red = accent.red * 0.45f + baseBg.red * 0.55f,
        green = accent.green * 0.45f + baseBg.green * 0.55f,
        blue = accent.blue * 0.45f + baseBg.blue * 0.55f,
        alpha = 1f
    )
    val midOpaque = Color(
        red = accent.red * 0.24f + baseBg.red * 0.76f,
        green = accent.green * 0.24f + baseBg.green * 0.76f,
        blue = accent.blue * 0.24f + baseBg.blue * 0.76f,
        alpha = 1f
    )
    val bottomOpaque = Color(
        red = accent.red * 0.14f + baseBg.red * 0.86f,
        green = accent.green * 0.14f + baseBg.green * 0.86f,
        blue = accent.blue * 0.14f + baseBg.blue * 0.86f,
        alpha = 1f
    )
    return listOf(topOpaque, midOpaque, bottomOpaque, baseBg)
}


/**
 * Animated musical wave gradient brush: simulates acoustic wave motion (oscillating between dark and lighter
 * accent tones) with fluid harmonic pulses. Completely flat without static background figures or logos.
 */
@Composable
fun rememberGlassyWaveBrush(accent: Color): Brush {
    val transition = rememberInfiniteTransition(label = "musicWave")
    val waveOffset by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveOffset"
    )
    val waveHarmonic by transition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveHarmonic"
    )
    val waveBrightness by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveBrightness"
    )

    val baseBg = Color(0xFF080A12)
    val topColor = Color(
        red = accent.red * waveBrightness + baseBg.red * (1f - waveBrightness),
        green = accent.green * waveBrightness + baseBg.green * (1f - waveBrightness),
        blue = accent.blue * waveBrightness + baseBg.blue * (1f - waveBrightness),
        alpha = 1f
    )
    val midColor = Color(
        red = accent.red * (waveBrightness * 0.55f) + baseBg.red * (1f - waveBrightness * 0.55f),
        green = accent.green * (waveBrightness * 0.55f) + baseBg.green * (1f - waveBrightness * 0.55f),
        blue = accent.blue * (waveBrightness * 0.55f) + baseBg.blue * (1f - waveBrightness * 0.55f),
        alpha = 1f
    )
    val lowColor = Color(
        red = accent.red * 0.12f + baseBg.red * 0.88f,
        green = accent.green * 0.12f + baseBg.green * 0.88f,
        blue = accent.blue * 0.12f + baseBg.blue * 0.88f,
        alpha = 1f
    )

    return Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to topColor,
            waveOffset to midColor,
            waveHarmonic to lowColor,
            1.0f to baseBg
        )
    )
}

/** Glassy card modifier: translucent white surface, crisp gradient glass border, and soft glowing shadow. */
fun Modifier.glassyCard(
    isGlassy: Boolean,
    shape: Shape = RoundedCornerShape(14.dp),
    accent: Color = Color.Transparent,
    alpha: Float = 0.14f,
    borderAlpha: Float = 0.35f,
    shadowElevation: Dp = 6.dp,
): Modifier = if (isGlassy) {
    this
        .shadow(shadowElevation, shape, spotColor = if (accent != Color.Transparent) accent.copy(alpha = 0.25f) else Color.Black)
        .clip(shape)
        .background(Color.White.copy(alpha = alpha))
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = borderAlpha), Color.White.copy(alpha = borderAlpha * 0.35f))
            ),
            shape
        )
} else {
    this
}

/** Glassy circular button modifier: frosted translucent circular container with glass border and soft shadow. */
fun Modifier.glassyButton(
    isGlassy: Boolean,
    size: Dp = 40.dp,
    accent: Color = Color.Transparent,
): Modifier = if (isGlassy) {
    this
        .size(size)
        .shadow(4.dp, CircleShape, spotColor = if (accent != Color.Transparent) accent.copy(alpha = 0.35f) else Color.Black)
        .clip(CircleShape)
        .background(Color.White.copy(alpha = 0.14f))
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.15f))
            ),
            CircleShape
        )
} else {
    this
}

fun onColor(c: Color): Color {
    val l = 0.299 * c.red + 0.587 * c.green + 0.114 * c.blue
    return if (l > 0.6) Color.Black else Color.White
}

@Composable
fun SpotifreeTheme(accentHex: String, amoled: Boolean, uiTheme: String = "default", content: @Composable () -> Unit) {
    val isGlassy = (uiTheme == "glassy")
    val accent = parseColor(accentHex)
    // Glassy: translucent surfaces and borders over ambient glowing gradient background.
    // AMOLED: pure black backgrounds (pixels off) with slightly lifted cards so they stay visible.
    val bg = when {
        isGlassy -> Color.Transparent
        amoled -> Color.Black
        else -> Color(0xFF121212)
    }
    val surface = when {
        isGlassy -> Color(0x22FFFFFF)
        amoled -> Color(0xFF0A0A0A)
        else -> Color(0xFF181818)
    }
    val elevated = when {
        isGlassy -> Color(0x32FFFFFF)
        amoled -> Color(0xFF161616)
        else -> Color(0xFF282828)
    }
    val chip = when {
        isGlassy -> Color(0x28FFFFFF)
        else -> Color(0x1AFFFFFF)
    }
    val glassBorder = if (isGlassy) Color(0x45FFFFFF) else Color.Transparent
    val glassHighlight = if (isGlassy) Color(0x80FFFFFF) else Color.Transparent
    val textSecondary = if (isGlassy) Color(0xCCFFFFFF) else Color(0xFFB3B3B3)
    val textMuted = if (isGlassy) Color(0x99FFFFFF) else Color(0xFF727272)

    val colors = SpotifreeColors(
        accent = accent,
        background = bg,
        surface = surface,
        elevated = elevated,
        textSecondary = textSecondary,
        amoled = if (isGlassy) false else amoled,
        textMuted = textMuted,
        chip = chip,
        isGlassy = isGlassy,
        glassBorder = glassBorder,
        glassHighlight = glassHighlight,
    )
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = onColor(accent),
        secondary = accent,
        onSecondary = onColor(accent),
        background = if (isGlassy) Color.Transparent else bg,
        onBackground = Color.White,
        surface = if (isGlassy) surface else bg,
        onSurface = Color.White,
        surfaceVariant = elevated,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerLow = if (isGlassy) Color(0x18FFFFFF) else surface,
        surfaceContainerLowest = if (isGlassy) Color.Transparent else bg,
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = if (isGlassy) Color(0x40FFFFFF) else if (amoled) Color(0xFF1E1E1E) else Color(0xFF333333),
        outline = if (isGlassy) glassBorder else Color(0xFF535353),
        outlineVariant = if (isGlassy) Color(0x24FFFFFF) else Color(0xFF3E3E3E),
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
