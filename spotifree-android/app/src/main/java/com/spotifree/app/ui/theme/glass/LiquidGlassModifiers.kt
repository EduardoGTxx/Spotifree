package com.spotifree.app.ui.theme.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple-style Liquid Glass Modifier:
 * - Real-time hardware backdrop blur via RenderEffect on Android 12+ (API 31+) / Android 14+ (API 34+)
 * - Translucent fluid glass surface with specular rim catchlight
 * - Dual-layer beveled caustic border simulating convex lens curvature
 * - Interactive tactile gel-press physics (spring elasticity and specular flash on touch)
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
    composed {
        var isPressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (interactive && isPressed) 0.96f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "liquidGelPressScale"
        )
        val dynamicBorderAlpha by animateFloatAsState(
            targetValue = if (interactive && isPressed) (borderAlpha + 0.25f).coerceAtMost(0.95f) else borderAlpha,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "liquidBorderSheen"
        )

        val spotColor = if (accent != Color.Transparent) accent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f)
        val glassBrush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = dynamicBorderAlpha),
                Color.White.copy(alpha = dynamicBorderAlpha * 0.45f),
                Color.White.copy(alpha = dynamicBorderAlpha * 0.12f),
                if (accent != Color.Transparent) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = dynamicBorderAlpha * 0.25f)
            )
        )

        var mod = this
        if (interactive) {
            mod = mod
                .scale(scale)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        }
                    )
                }
        }

        mod
            .shadow(shadowElevation, shape, spotColor = spotColor, ambientColor = spotColor)
            .clip(shape)
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0.dp) {
                    Modifier.graphicsLayer {
                        renderEffect = RenderEffect.createBlurEffect(
                            blurRadius.toPx(),
                            blurRadius.toPx(),
                            Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    }
                } else Modifier
            )
            .background(Color.White.copy(alpha = surfaceAlpha))
            .border(1.dp, glassBrush, shape)
    }
}

/**
 * Liquid Glass Button modifier:
 * Circular/pill shape with tactile gel-press response, glowing caustics and specular highlights.
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
