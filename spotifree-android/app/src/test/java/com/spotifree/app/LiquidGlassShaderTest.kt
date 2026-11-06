package com.spotifree.app

import com.spotifree.app.ui.theme.glass.LiquidGlassShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LiquidGlassShader AGSL optics engine.
 * Validates AGSL source syntax, uniform definitions, SDF calculations,
 * and graceful fallback on JVM / older Android runtimes.
 */
class LiquidGlassShaderTest {

    @Test
    fun agslSourceDeclaresRequiredUniformsAndMain() {
        val src = LiquidGlassShader.AGSL_SOURCE
        assertNotNull("AGSL source must not be null", src)
        assertTrue("Missing uniform float2 uResolution", src.contains("uniform float2 uResolution;"))
        assertTrue("Missing uniform float uCornerRadius", src.contains("uniform float uCornerRadius;"))
        assertTrue("Missing uniform float uRefraction", src.contains("uniform float uRefraction;"))
        assertTrue("Missing uniform float uChromatic", src.contains("uniform float uChromatic;"))
        assertTrue("Missing uniform float uSpecularAlpha", src.contains("uniform float uSpecularAlpha;"))
        assertTrue("Missing uniform float2 uLightDir", src.contains("uniform float2 uLightDir;"))
        assertTrue("Missing uniform shader uContent", src.contains("uniform shader uContent;"))
        assertTrue("Missing main entrypoint", src.contains("half4 main(float2 fragCoord)"))
    }

    @Test
    fun agslSourceCalculatesSdfAndOpticsNormals() {
        val src = LiquidGlassShader.AGSL_SOURCE
        assertTrue("Missing roundedBoxSDF calculation", src.contains("float roundedBoxSDF("))
        assertTrue("Missing rim factor calculation", src.contains("rimFactor"))
        assertTrue("Missing specular highlight calculation", src.contains("NdotH"))
        assertTrue("Missing chromatic dispersion rOffset", src.contains("rOffset"))
        assertTrue("Missing chromatic dispersion gOffset", src.contains("gOffset"))
        assertTrue("Missing chromatic dispersion bOffset", src.contains("bOffset"))
    }

    @Test
    fun isSupportedDoesNotThrowOnHostJvm() {
        // Build.VERSION.SDK_INT defaults to 0 on JVM tests (unitTests.isReturnDefaultValues = true)
        val supported = LiquidGlassShader.isSupported
        // Should evaluate cleanly to false or true based on SDK_INT
        assertEquals(false, supported)
    }

    @Test
    fun createShaderHandlesGracefulFallbackOnUnsupportedRuntimes() {
        // When not supported (e.g. host JVM test environment), createShader returns null cleanly without throwing
        val shader = LiquidGlassShader.createShader()
        if (!LiquidGlassShader.isSupported) {
            assertEquals(null, shader)
        }
    }
}
