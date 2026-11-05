# Apple Liquid Glass UI/UX Implementation Plan for Spotifree

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the Spotifree "Glassy" theme into a genuine Apple Liquid Glass UI/UX system featuring AGSL runtime shaders, real-time optical refraction, chromatic dispersion, specular caustic highlights, hardware backdrop blur (`RenderEffect`), interactive gel-press touch physics, and a floating capsule navigation bar on Android 14+ / Android 17.

**Architecture:** A layered optics engine built on Android Graphics Shading Language (AGSL) and Jetpack Compose `drawWithCache` / `graphicsLayer`. The core shader calculates signed distance fields (SDF) of rounded geometry, derives pseudo-3D surface normals to simulate convex lens curvature, and projects light reflections and refraction offsets with chromatic aberration. This is wrapped in reusable Compose modifiers (`Modifier.liquidGlass`, `Modifier.liquidGlassButton`, `Modifier.liquidGlassCard`) with spring-animated touch deformation ("gel-press") and applied across navigation, player, cards, lists, and sheets.

**Tech Stack:** Kotlin 2.x, Jetpack Compose (Material3), AGSL (`android.graphics.RuntimeShader`), Android Hardware Graphics (`android.graphics.RenderEffect`), Compose Animation (`rememberInfiniteTransition`, `animateFloatAsState`, `spring`), Android SDK 34-36 (Targeting Android 14+ through Android 17 / Pixel 9a).

**Spec:** User specifications based on Apple Liquid Glass design principles, visionOS optics, open-source references (`Abdullajon1881/LiquidGlass`, `socket-link/fluid-krystal`, `Clash-Projects/LastWave-Native`), and curated Dribbble designs (shots 26926004, 26174948, 26391928, 26170077, 27837898, 27177396, 26872452, 16197763).

## Global Constraints

- **Platform Target:** Android 14+ (API 34+) baseline; must utilize native AGSL (`RuntimeShader`) and `RenderEffect` with graceful fallback for any API < 33.
- **Performance Budget:** 60/120 FPS fluid render target without frame drops during scroll and animations; avoid redundant shader allocations by caching `RuntimeShader` instances.
- **Theme Invariant:** Changes must be gated on `c.isGlassy` / `uiTheme == "glassy"` so standard Dark, Black AMOLED, and other color presets remain completely intact.
- **Preserve Existing Functionality:** Retain all playback controls, gestures, drag gestures, download actions, menus, routing, and lifecycle events.
- **Testing:** Unit tests must pass with `./gradlew testDebugUnitTest` and compilation with `./gradlew compileDebugKotlin`.

## Review Focus

1. **Shader Resource Leaks / Re-creation:** Caching shader instances per composition to prevent allocating `RuntimeShader` on every frame draw.
2. **Hit Target Clipping:** Ensuring floating capsule navigation and pill modifiers do not clip pointer events or touch delegate bounds.
3. **Hardware Acceleration Compatibility:** Guarding `RenderEffect` and `RuntimeShader` calls with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` (API 33).
4. **Contrast & Legibility:** Maintaining WCAG-compliant contrast for typography and icons over dynamically refracting colored backgrounds.
5. **Memory and Overscroll Bounds:** Preventing visual artifacts or texture stretches when lists overscroll against floating blurred containers.

---

### Task 1: AGSL Optics Shader & Lens Engine

**Files:**
- Create: `spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassShader.kt`
- Create: `spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt`

**Interfaces:**
- Produces:
  - `object LiquidGlassShader`
  - `fun LiquidGlassShader.isSupported(): Boolean`
  - `fun LiquidGlassShader.createShader(width: Float, height: Float, cornerRadius: Float, refraction: Float, chromaticAberration: Float, lightAngleRad: Float): android.graphics.RuntimeShader?`
  - `const val LIQUID_GLASS_AGSL_SOURCE: String`

- [ ] **Step 1: Write the unit test for AGSL shader syntax and uniform validations**

```kotlin
// spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt
package com.spotifree.app

import com.spotifree.app.ui.theme.glass.LIQUID_GLASS_AGSL_SOURCE
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassTest {
    @Test
    fun agslSourceDeclaresRequiredUniformsAndMain() {
        val src = LIQUID_GLASS_AGSL_SOURCE
        assertTrue(src.contains("uniform float2 uResolution;"))
        assertTrue(src.contains("uniform float uCornerRadius;"))
        assertTrue(src.contains("uniform float uRefraction;"))
        assertTrue(src.contains("uniform float uChromatic;"))
        assertTrue(src.contains("uniform float uSpecularAlpha;"))
        assertTrue(src.contains("half4 main(float2 fragCoord)"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.LiquidGlassTest"`
Expected: FAIL (unresolved reference `LiquidGlassTest` / `LIQUID_GLASS_AGSL_SOURCE`).

- [ ] **Step 3: Implement `LiquidGlassShader.kt` with AGSL lens math and runtime shader factory**

```kotlin
// spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassShader.kt
package com.spotifree.app.ui.theme.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

const val LIQUID_GLASS_AGSL_SOURCE = """
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

object LiquidGlassShader {
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createShader(
        width: Float,
        height: Float,
        cornerRadius: Float,
        refraction: Float = 0.45f,
        chromatic: Float = 0.25f,
        specularAlpha: Float = 0.55f,
        lightDirX: Float = -0.4f,
        lightDirY: Float = -0.6f,
    ): RuntimeShader {
        val shader = RuntimeShader(LIQUID_GLASS_AGSL_SOURCE)
        shader.setFloatUniform("uResolution", width, height)
        shader.setFloatUniform("uCornerRadius", cornerRadius)
        shader.setFloatUniform("uRefraction", refraction)
        shader.setFloatUniform("uChromatic", chromatic)
        shader.setFloatUniform("uSpecularAlpha", specularAlpha)
        shader.setFloatUniform("uLightDir", lightDirX, lightDirY)
        return shader
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.LiquidGlassTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassShader.kt spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt
git commit -m "feat(ui): add AGSL Liquid Glass runtime shader engine and test suite"
```

---

### Task 2: Liquid Glass Modifiers with Gel-Press Physics

**Files:**
- Create: `spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassModifiers.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/Theme.kt`
- Test: `spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt`

**Interfaces:**
- Consumes: `LiquidGlassShader` from Task 1
- Produces:
  - `fun Modifier.liquidGlass(isGlassy: Boolean, shape: Shape = RoundedCornerShape(16.dp), accent: Color = Color.Transparent, blurRadius: Dp = 20.dp, refraction: Float = 0.4f, interactive: Boolean = false): Modifier`
  - `fun Modifier.liquidGlassButton(isGlassy: Boolean, size: Dp = 40.dp, accent: Color = Color.Transparent, interactive: Boolean = true): Modifier`
  - `fun Modifier.liquidGlassCard(isGlassy: Boolean, shape: Shape = RoundedCornerShape(16.dp), accent: Color = Color.Transparent, interactive: Boolean = false): Modifier`

- [ ] **Step 1: Write test for modifier helper math & parameter bounds**

```kotlin
// In spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt
@Test
fun testLiquidGlassPhysicsParams() {
    val normalScale = 1.0f
    val pressedScale = 0.96f
    assertTrue(pressedScale < normalScale)
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.LiquidGlassTest"`
Expected: PASS.

- [ ] **Step 3: Implement `LiquidGlassModifiers.kt`**

Create `LiquidGlassModifiers.kt` with:
1. `liquidGlass(...)`: Applies hardware blur via `graphicsLayer { if (Build.VERSION.SDK_INT >= 31) renderEffect = RenderEffect.createBlurEffect(...) }`, dual-layer specular gradient border (light on top/left, soft shade on bottom/right), and optional AGSL shader lens distortion.
2. Interactive `gel-press` tactile feedback: detects touch down/up, smoothly animating scale `0.96f` and brightening border specular highlight using `spring(dampingRatio = Spring.DampingRatioMediumBouncy)`.
3. `liquidGlassButton(...)` and `liquidGlassCard(...)` convenience extensions.

- [ ] **Step 4: Update `Theme.kt` to re-export and link the new liquid glass modifiers**

Modify `Theme.kt` lines 170-220 to delegate `Modifier.glassyCard` and `Modifier.glassyButton` to `Modifier.liquidGlassCard` and `Modifier.liquidGlassButton`.

- [ ] **Step 5: Verify build & tests**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat compileDebugKotlin testDebugUnitTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassModifiers.kt spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/Theme.kt spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt
git commit -m "feat(ui): implement Modifier.liquidGlass with hardware blur, caustics, and gel-press physics"
```

---

### Task 3: Floating Capsule Liquid Navigation Bar

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/MainScreen.kt`

**Interfaces:**
- Consumes: `Modifier.liquidGlass` from Task 2
- Modifies: `fun BottomBar(tabs: List<Tab>, selected: (Tab) -> Boolean, onSelect: (Tab) -> Unit)` in `MainScreen.kt`

- [ ] **Step 1: Inspect and design floating pill bottom nav layout in `MainScreen.kt`**

Current implementation in lines 408-460 uses a full-width docked row.
The Liquid Glass navigation bar will:
1. Detach from bottom and horizontal edges: `Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()`.
2. Encapsulate in a continuous capsule shape (`RoundedCornerShape(32.dp)` or `CircleShape`).
3. Apply `Modifier.liquidGlass(isGlassy = c.isGlassy, shape = CircleShape, blurRadius = 24.dp, refraction = 0.5f)`.
4. Include an animated liquid sliding indicator pill beneath the active tab that smoothly glides and squashes horizontally using `spring(dampingRatio = Spring.DampingRatioLowBouncy)`.
5. Display active icon with vibrant white/accent tint and subtle glow; inactive icons in soft translucent white (`0.55f`).

- [ ] **Step 2: Implement floating capsule `BottomBar` in `MainScreen.kt`**

Replace lines 408-460 of `MainScreen.kt` with the floating capsule design. Ensure `Scaffold` has transparent container color and padding allows list items to scroll behind the floating capsule.

- [ ] **Step 3: Verify build**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat compileDebugKotlin"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add spotifree-android/app/src/main/java/com/spotifree/app/ui/MainScreen.kt
git commit -m "feat(ui): convert bottom navigation bar to floating liquid glass capsule with spring sliding pill"
```

---

### Task 4: Floating Liquid MiniPlayer & Full NowPlaying Player

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/player/MiniPlayer.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/player/NowPlayingScreen.kt`

**Interfaces:**
- Consumes: `Modifier.liquidGlass`, `Modifier.liquidGlassButton` from Task 2
- Updates: `MiniPlayer` floating dock layout and `NowPlayingScreen` controls, seekbar, and artwork frame.

- [ ] **Step 1: Update `MiniPlayer.kt` to floating liquid glass pill**

1. Increase corner radius to `26.dp` for a smooth capsule appearance.
2. Apply `Modifier.liquidGlass(c.isGlassy, shape = RoundedCornerShape(26.dp), blurRadius = 20.dp, refraction = 0.45f)`.
3. Add a thin liquid progress line integrated along the bottom curve with an animated glowing droplet head at the current playback position.
4. Style Play/Pause and Like buttons with gel-press tactile feedback.

- [ ] **Step 2: Update `NowPlayingScreen.kt` controls & seekbar**

1. **Liquid Seekbar**: In `Controls()`, enhance the progress slider with a liquid capsule track: 6dp height with rounded caps, glowing accent fill, and a glass bead scrubber thumb with specular highlight.
2. **Play/Pause Hero Button**: Replace flat circle with a liquid glass floating orb: `72.dp` diameter, concentric specular bevels, radial liquid accent gradient, and gel-press response.
3. **Secondary Action Pills**: Speed, sleep timer, lyrics, and EQ pills styled with capsule liquid glass borders.
4. **Artwork Frame**: Surround album cover with a 2.5D frosted liquid glass bezel with subtle optical refraction rimming.

- [ ] **Step 3: Verify build & tests**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat compileDebugKotlin testDebugUnitTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add spotifree-android/app/src/main/java/com/spotifree/app/ui/player/MiniPlayer.kt spotifree-android/app/src/main/java/com/spotifree/app/ui/player/NowPlayingScreen.kt
git commit -m "feat(ui): style MiniPlayer and NowPlayingScreen with liquid glass capsules, hero orb, and bead seekbar"
```

---

### Task 5: Cards, Shelves, Category Cards & Search

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/common/Spotify.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/common/Components.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/search/SearchScreen.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `Modifier.liquidGlassCard`, `Modifier.liquidGlassButton` from Task 2
- Updates: `CategoryCard`, `ActionPill`, `OutlinePill`, `ShelfCard`, `QuickTile`, `SearchField`.

- [ ] **Step 1: Refactor `CategoryCard`, `ActionPill`, and `OutlinePill` in `Spotify.kt`**

1. `CategoryCard`: Add 2.5D tilted inner icon glass container with specular perimeter highlight and subtle refraction displacement.
2. `ActionPill`: Full liquid capsule with dynamic border caustic sheen (`1.2dp` gradient border) and gel-press touch interaction.
3. `OutlinePill`: Crystal translucent outline with ultra-crisp white glass border.

- [ ] **Step 2: Update `ShelfCard` in `Components.kt` and `QuickTile` in `HomeScreen.kt`**

1. `ShelfCard`: Apply `Modifier.liquidGlassCard` with subtle curvature highlight and elevated shadow.
2. `QuickTile`: Enhance split cover/title shortcut with liquid glass boundary and rounded inner clip.

- [ ] **Step 3: Update `SearchScreen.kt` input pill**

1. Search text field transformed into a floating liquid glass capsule with `Modifier.liquidGlass(c.isGlassy, shape = CircleShape)`.
2. Ambient colored glow on focus that responds to `c.accent`.

- [ ] **Step 4: Verify build & tests**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat compileDebugKotlin testDebugUnitTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add spotifree-android/app/src/main/java/com/spotifree/app/ui/common/Spotify.kt spotifree-android/app/src/main/java/com/spotifree/app/ui/common/Components.kt spotifree-android/app/src/main/java/com/spotifree/app/ui/search/SearchScreen.kt spotifree-android/app/src/main/java/com/spotifree/app/ui/home/HomeScreen.kt
git commit -m "feat(ui): elevate cards, pills, search field and shelves to liquid glass aesthetic"
```

---

### Task 6: Liquid Glass Modal Sheets & Overlays

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/common/Sheets.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/player/PlayerSheets.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/home/RecognitionSheet.kt`

**Interfaces:**
- Consumes: `Modifier.liquidGlass` from Task 2
- Updates: Modal bottom sheet containers, drag handle, dialog surfaces, recognition microphone orb.

- [ ] **Step 1: Enhance `ModalBottomSheet` containers and drag handles**

1. Custom `sheetState` and container decoration: `RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)` with top-edge specular line (`0.85dp` gradient catch-light) simulating curved sheet glass.
2. Background color: Deep frosted translucent midnight tint with hardware blur.
3. Drag handle: Translucent capsule bead with glowing glass edge.

- [ ] **Step 2: Upgrade `RecognitionSheet.kt` microphone orb**

1. Central recording button: Multi-layer concentric liquid glass ripples that expand and refract based on the live audio amplitude `scale` factor.
2. Ambient acoustic glow pulsating with `c.accent`.

- [ ] **Step 3: Verify build & tests**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat compileDebugKotlin testDebugUnitTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add spotifree-android/app/src/main/java/com/spotifree/app/ui/common/Sheets.kt spotifree-android/app/src/main/java/com/spotifree/app/ui/player/PlayerSheets.kt spotifree-android/app/src/main/java/com/spotifree/app/ui/home/RecognitionSheet.kt
git commit -m "feat(ui): implement liquid glass styling for modal sheets, drag handles, and acoustic recognition orb"
```

---

### Task 7: Verification, Performance Profiling & Documentation

**Files:**
- Modify: `spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt`
- Modify: `README.md` (Spotifree)

- [ ] **Step 1: Run complete test suite and Gradle build**

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat testDebugUnitTest"`
Expected: BUILD SUCCESSFUL (all unit tests passing).

Run: `cmd /c "cd /d c:\Users\edhernandez\Downloads\Spotifree\Spotifree\spotifree-android && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL (debug APK assembled without compilation or linking warnings).

- [ ] **Step 2: Document the Apple Liquid Glass design system in docs**

Add a brief section in Spotifree documentation detailing the AGSL optics parameters, hardware blur requirements, and design tokens for future extension.

- [ ] **Step 3: Commit & Push**

```bash
git add spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassTest.kt README.md
git commit -m "chore(ui): finalize Apple Liquid Glass UI system verification and documentation"
git push origin main
```
