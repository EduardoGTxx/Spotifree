package com.spotifree.app.ui.theme.glass

import android.graphics.RuntimeShader
import android.os.Build

/**
 * LiquidGlassShader:
 * Android Graphics Shading Language (AGSL) optics runtime shader engine.
 *
 * Implements:
 * 1. Signed Distance Field (SDF) of rounded rectangles and capsules with anti-aliased edge masking.
 * 2. Surface normal synthesis simulating convex lens refractive curvature.
 * 3. Fresnel specular catchlights and caustic rim highlights driven by dynamic incident light vectors.
 * 4. Chromatic dispersion / aberration refracting background pixels through R, G, and B channel displacement.
 */
object LiquidGlassShader {

    /**
     * RuntimeShader requires Android 13 (API 33, Tiramisu) or higher.
     * Android 14+ (API 34-36) provides full hardware AGSL acceleration with RenderEffect chaining.
     */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * High-fidelity AGSL shader source.
     */
    const val AGSL_SOURCE: String = """
    uniform float2 uResolution;
    uniform float uCornerRadius;
    uniform float uRefraction;
    uniform float uChromatic;
    uniform float uSpecularAlpha;
    uniform float2 uLightDir;
    uniform shader uContent;

    float roundedBoxSDF(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + r;
        return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
    }

    half4 main(float2 fragCoord) {
        float2 halfRes = uResolution * 0.5;
        float2 p = fragCoord - halfRes;
        float dist = roundedBoxSDF(p, halfRes, uCornerRadius);
        if (dist > 0.0) {
            return half4(0.0);
        }

        // Lens curvature normal
        float edgeDist = -dist;
        float rimWidth = max(uCornerRadius * 0.45, 12.0);
        float rimFactor = clamp(edgeDist / rimWidth, 0.0, 1.0);
        float normalZ = sqrt(1.0 - (1.0 - rimFactor) * (1.0 - rimFactor));
        float2 normalXY = normalize(p + 0.0001) * (1.0 - rimFactor);
        float3 N = normalize(float3(normalXY, normalZ));

        // Specular caustic reflection (top-left incident light)
        float3 L = normalize(float3(uLightDir, 0.85));
        float3 H = normalize(L + float3(0.0, 0.0, 1.0));
        float NdotH = max(dot(N, H), 0.0);
        float specular = pow(NdotH, 28.0) * uSpecularAlpha;

        // Thin perimeter rim catchlight
        float rimGlow = pow(1.0 - rimFactor, 3.5) * 0.45;

        // Refraction displacement with chromatic aberration
        float refrDist = (1.0 - rimFactor) * uRefraction * 14.0;
        float2 rOffset = normalXY * refrDist;
        float2 gOffset = normalXY * (refrDist * (1.0 - uChromatic * 0.3));
        float2 bOffset = normalXY * (refrDist * (1.0 + uChromatic * 0.5));

        half4 colorR = uContent.eval(fragCoord + rOffset);
        half4 colorG = uContent.eval(fragCoord + gOffset);
        half4 colorB = uContent.eval(fragCoord + bOffset);

        half4 refracted = half4(colorR.r, colorG.g, colorB.b, (colorR.a + colorG.a + colorB.a) / 3.0);
        half4 highlight = half4(1.0, 1.0, 1.0, 1.0) * (specular + rimGlow);

        return refracted + highlight * highlight.a;
    }
"""

    /**
     * Instantiates an AGSL RuntimeShader if supported on the current device.
     * Returns null safely on unsupported devices (API < 33 or host JVM).
     */
    fun createShader(): RuntimeShader? {
        return if (isSupported) {
            try {
                RuntimeShader(AGSL_SOURCE)
            } catch (e: Throwable) {
                null
            }
        } else {
            null
        }
    }

    /**
     * Efficiently updates shader uniforms in-place without reallocating shader objects.
     * Must be called inside drawWithCache or drawWithContent to maintain 60/120 FPS.
     */
    fun updateUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        cornerRadius: Float,
        refraction: Float = 0.45f,
        chromatic: Float = 0.25f,
        specularAlpha: Float = 0.55f,
        lightDirX: Float = -0.4f,
        lightDirY: Float = -0.6f,
    ) {
        if (!isSupported) return
        shader.setFloatUniform("uResolution", width, height)
        shader.setFloatUniform("uCornerRadius", cornerRadius)
        shader.setFloatUniform("uRefraction", refraction)
        shader.setFloatUniform("uChromatic", chromatic)
        shader.setFloatUniform("uSpecularAlpha", specularAlpha)
        shader.setFloatUniform("uLightDir", lightDirX, lightDirY)
    }
}
