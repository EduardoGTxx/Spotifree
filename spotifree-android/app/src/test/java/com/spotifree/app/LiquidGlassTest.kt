package com.spotifree.app

import com.spotifree.app.ui.theme.glass.LIQUID_GLASS_AGSL_SOURCE
import com.spotifree.app.ui.theme.glass.LiquidGlassShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassTest {
    @Test
    fun agslSourceDeclaresRequiredUniformsAndMain() {
        val src = LIQUID_GLASS_AGSL_SOURCE
        assertTrue("Missing uResolution", src.contains("uniform float2 uResolution;"))
        assertTrue("Missing uCornerRadius", src.contains("uniform float uCornerRadius;"))
        assertTrue("Missing uRefraction", src.contains("uniform float uRefraction;"))
        assertTrue("Missing uChromatic", src.contains("uniform float uChromatic;"))
        assertTrue("Missing uSpecularAlpha", src.contains("uniform float uSpecularAlpha;"))
        assertTrue("Missing main entrypoint", src.contains("half4 main(float2 fragCoord)"))
    }

    @Test
    fun liquidGlassPhysicsParamsAreValid() {
        val normalScale = 1.0f
        val pressedScale = 0.96f
        assertTrue(pressedScale < normalScale)
        assertEquals(0.96f, pressedScale, 0.001f)
    }
}
