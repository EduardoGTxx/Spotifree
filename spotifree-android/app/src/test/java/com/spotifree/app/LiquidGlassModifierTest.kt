package com.spotifree.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spotifree.app.ui.theme.glass.LiquidGlassDefaults
import com.spotifree.app.ui.theme.glass.gelPressClickable
import com.spotifree.app.ui.theme.glass.liquidGlass
import com.spotifree.app.ui.theme.glass.liquidGlassButton
import com.spotifree.app.ui.theme.glass.liquidGlassCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LiquidGlassModifiers and gel-press tactile physics.
 * Tests modifier chaining, theme gating, GPU memory safe bounds,
 * and Apple Liquid Glass spring parameters.
 */
class LiquidGlassModifierTest {

    @Test
    fun liquidGlassReturnsIdentityWhenNotGlassy() {
        val initial = Modifier
        val modified = initial.liquidGlass(isGlassy = false)
        assertEquals("Modifier should not be altered when isGlassy is false", initial, modified)
    }

    @Test
    fun liquidGlassButtonReturnsIdentityWhenNotGlassy() {
        val initial = Modifier
        val modified = initial.liquidGlassButton(isGlassy = false)
        assertEquals("Modifier should not be altered when isGlassy is false", initial, modified)
    }

    @Test
    fun liquidGlassCardReturnsIdentityWhenNotGlassy() {
        val initial = Modifier
        val modified = initial.liquidGlassCard(isGlassy = false)
        assertEquals("Modifier should not be altered when isGlassy is false", initial, modified)
    }

    @Test
    fun gelPressPhysicsConstantsMeetSpec() {
        assertEquals(0.93f, LiquidGlassDefaults.PRESSED_SCALE, 0.001f)
        assertEquals(0.55f, LiquidGlassDefaults.SPRING_DAMPING, 0.001f)
        assertEquals(600f, LiquidGlassDefaults.SPRING_STIFFNESS, 0.001f)
        assertEquals(0.30f, LiquidGlassDefaults.PRESS_SPECULAR_BOOST, 0.001f)
        assertTrue(LiquidGlassDefaults.PRESSED_SCALE < 1.0f)
    }

    @Test
    fun blurRadiusIsCoercedToGpuSafeLimits() {
        val unsafeRadius = 64.dp
        val safeRadius = LiquidGlassDefaults.coerceSafeBlurRadius(unsafeRadius)
        assertEquals(24.dp, safeRadius)

        val standardRadius = 16.dp
        assertEquals(16.dp, LiquidGlassDefaults.coerceSafeBlurRadius(standardRadius))

        val negativeRadius = (-5).dp
        assertEquals(0.dp, LiquidGlassDefaults.coerceSafeBlurRadius(negativeRadius))
    }

    @Test
    fun specularPulseBoostCalculatesCorrectAlpha() {
        val baseAlpha = 0.45f
        val activeAlpha = LiquidGlassDefaults.calculateActiveBorderAlpha(baseAlpha, isPressed = true)
        assertEquals(0.75f, activeAlpha, 0.001f)

        val unpressedAlpha = LiquidGlassDefaults.calculateActiveBorderAlpha(baseAlpha, isPressed = false)
        assertEquals(0.45f, unpressedAlpha, 0.001f)
    }

    @Test
    fun modifiersComposeWithoutThrowing() {
        val mod1 = Modifier.liquidGlass(isGlassy = true, accent = Color.Cyan)
        assertNotNull(mod1)

        val mod2 = Modifier.liquidGlassButton(isGlassy = true, size = 48.dp)
        assertNotNull(mod2)

        val mod3 = Modifier.liquidGlassCard(isGlassy = true, shadowElevation = 12.dp)
        assertNotNull(mod3)
    }
}
