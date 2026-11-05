package com.spotifree.app.ui.theme.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple-style Liquid Glass Modifier:
 * - Translucent fluid glass surface with specular rim catchlight
 * - Specular vertical gradient border reflecting top-down incident light
 * - Elevated soft depth shadow with optional accent color tinting
 *
 * Child content (icons, text, album covers) remains 100% sharp and legible because
 * hardware blur (RenderEffect) is avoided on the Composable tree, and touch gestures
 * pass cleanly to child buttons and items without pointer interception.
 */
fun Modifier.liquidGlass(
    isGlassy: Boolean,
    shape: Shape = RoundedCornerShape(16.dp),
    accent: Color = Color.Transparent,
    surfaceAlpha: Float = 0.16f,
    borderAlpha: Float = 0.45f,
    blurRadius: Dp = 16.dp,
    shadowElevation: Dp = 8.dp,
    interactive: Boolean = false,
): Modifier = if (!isGlassy) {
    this
} else {
    val spotColor = if (accent != Color.Transparent) accent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f)
    val glassBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = borderAlpha),
            Color.White.copy(alpha = borderAlpha * 0.45f),
            Color.White.copy(alpha = borderAlpha * 0.12f),
            if (accent != Color.Transparent) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = borderAlpha * 0.25f)
        )
    )

    this
        .shadow(shadowElevation, shape, spotColor = spotColor, ambientColor = spotColor)
        .clip(shape)
        .background(Color.White.copy(alpha = surfaceAlpha))
        .border(1.dp, glassBrush, shape)
}

/**
 * Liquid Glass Button modifier:
 * Circular/pill shape with glowing caustics and specular highlights.
 */
fun Modifier.liquidGlassButton(
    isGlassy: Boolean,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    accent: Color = Color.Transparent,
    interactive: Boolean = true,
): Modifier = if (!isGlassy) {
    this
} else {
    this
        .size(size)
        .liquidGlass(
            isGlassy = true,
            shape = shape,
            accent = accent,
            surfaceAlpha = 0.18f,
            borderAlpha = 0.50f,
            blurRadius = 12.dp,
            shadowElevation = 6.dp,
            interactive = interactive
        )
}

/**
 * Liquid Glass Card modifier:
 * Structured container for lists, dashboard cards, quick tiles and banners.
 */
fun Modifier.liquidGlassCard(
    isGlassy: Boolean,
    shape: Shape = RoundedCornerShape(16.dp),
    accent: Color = Color.Transparent,
    surfaceAlpha: Float = 0.14f,
    borderAlpha: Float = 0.38f,
    shadowElevation: Dp = 8.dp,
    interactive: Boolean = false,
): Modifier = if (!isGlassy) {
    this
} else {
    this.liquidGlass(
        isGlassy = true,
        shape = shape,
        accent = accent,
        surfaceAlpha = surfaceAlpha,
        borderAlpha = borderAlpha,
        blurRadius = 18.dp,
        shadowElevation = shadowElevation,
        interactive = interactive
    )
}
