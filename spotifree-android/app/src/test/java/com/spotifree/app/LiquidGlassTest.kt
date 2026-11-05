package com.spotifree.app

import com.spotifree.app.ui.theme.glass.LIQUID_GLASS_AGSL_SOURCE
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
}
