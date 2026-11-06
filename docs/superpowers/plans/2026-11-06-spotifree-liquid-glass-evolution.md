# Spotifree Liquid Glass & Audio Engine Evolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Elevate Spotifree's UI/UX to a true Apple Liquid Glass visual experience (inspired by Lelonio/Square and Clash-Projects/LastWave-Native) featuring AGSL runtime shader optics, hardware backdrop blur (`RenderEffect`), interactive gel-press touch physics, 100% glassy UI coverage across all components, and upgraded audio DSP capabilities (real environmental reverb, crossfade, and queue pre-caching).

**Architecture:** 
1. **Visual Optics:** A multi-layered Compose graphics pipeline leveraging Android 14+ (API 34-36) AGSL `RuntimeShader` for chromatic dispersion, Fresnel specular highlights, and real refractive SDF lens distortion over the animated wave background.
2. **Backdrop Blur:** Hardware-accelerated `RenderEffect.createBlurEffect` chained with the AGSL lens shader, safely guarded for API 33+ with zero overhead on non-glassy themes.
3. **Audio DSP:** Media3/ExoPlayer audio pipeline enhancement introducing Android's native `EnvironmentalReverb` audio effect and smooth volume crossfade transitions between tracks.

**Tech Stack:** Kotlin 2.2+, Jetpack Compose (BOM 2025.10.01), Material 3, Android AGSL (`android.graphics.RuntimeShader`), Android Graphics (`android.graphics.RenderEffect`), Media3 / ExoPlayer 1.5+, Android SDK 34-36 (Android 14+ / 17).

**Spec:** Audited against `Lelonio/Square` (Liquid Glass refraction, DSP reverb, crossfade) and `Clash-Projects/LastWave-Native` (Bit-perfect playback, multi-source lyrics, dynamic glass sheets).

## Global Constraints

- **Platform Target:** Android 14+ (API 34+) primary; all hardware shaders and blur effects must gracefully fall back to layered gradient brushes on API < 33.
- **Frame Rate Target:** Consistent 60/120 FPS fluid rendering on modern devices (e.g. Pixel 9a / Android 17); shader instances must be cached in `drawWithCache` to avoid per-frame allocations.
- **Theme Integrity:** All visual changes must be strictly gated on `LocalSpotifreeColors.current.isGlassy`; default Dark, Light, and AMOLED Black modes must remain 100% unimpacted.
- **Audio Lifecycle Safety:** Audio effects (Reverb, EQ) must attach safely to `audioSessionId` and release immediately on session transition or player release to prevent native memory leaks.
- **Testing Requirements:** `./gradlew testDebugUnitTest` must pass on every task deliverable.

## Review Focus

1. **Shader Allocation in Hot Loops:** Verify that `RuntimeShader` uniform updates happen inside `drawWithCache` lambdas without instantiating new shader objects during recomposition.
2. **Touch Bounds & Pointer Interception:** Ensure floating capsules, glass modifiers, and gel-press animations do not intercept clicks or cause drag conflicts with the seekbar or list scrolls.
3. **Contrast & Readability:** Validate WCAG text contrast (`textPrimary`, `textSecondary`) over dynamic refracting backgrounds.
4. **Hardware Blur Memory Footprint:** Ensure `RenderEffect` blur radius does not cause GPU buffer overruns during list overscroll.
5. **DSP Reverb Audio Glitches:** Ensure reverb parameters only apply when enabled and never produce clicks/pops during track changes.

---

### Task 1: AGSL Optics Shader & Lens Engine

**Files:**
- Create: `spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassShader.kt`
- Create: `spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassShaderTest.kt`

**Interfaces:**
```kotlin
object LiquidGlassShader {
    val isSupported: Boolean
    fun createShader(): android.graphics.RuntimeShader?
    const val AGSL_SOURCE: String
}
```

- [ ] **Step 1: Write unit test validating AGSL shader syntax and uniform requirements**
- [ ] **Step 2: Run test to confirm it fails** (`./gradlew testDebugUnitTest --tests com.spotifree.app.LiquidGlassShaderTest`)
- [ ] **Step 3: Implement `LiquidGlassShader.kt` with AGSL source calculating signed distance fields (SDF), surface normals, specular highlights, and chromatic aberration**
- [ ] **Step 4: Run test to confirm it passes**
- [ ] **Step 5: Commit changes** (`feat(glass): add AGSL liquid glass optics runtime shader`)

---

### Task 2: Hardware Backdrop Blur & RenderEffect Layer

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassModifiers.kt`
- Create: `spotifree-android/app/src/test/java/com/spotifree/app/LiquidGlassModifierTest.kt`

**Details:**
Upgrade `Modifier.liquidGlass` to combine:
1. Specular rim gradients and translucent background.
2. Android 13+ `RenderEffect.createBlurEffect` on the backing graphics layer when `isGlassy` is active.
3. Fallback to translucent frosted brush on older APIs.

- [ ] **Step 1: Write unit test for modifier resolution across glassy and non-glassy flags**
- [ ] **Step 2: Run test to confirm it fails**
- [ ] **Step 3: Update `LiquidGlassModifiers.kt` with `graphicsLayer` blur chaining**
- [ ] **Step 4: Run test to confirm it passes**
- [ ] **Step 5: Commit changes** (`feat(glass): add hardware RenderEffect backdrop blur to liquid glass modifiers`)

---

### Task 3: Interactive Tactile Gel-Press Physics Modifier

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/theme/glass/LiquidGlassModifiers.kt`

**Details:**
Add `Modifier.gelPressClickable` implementing Apple Liquid Glass touch physics:
- Spring-animated scale down to `0.93f` on touch down (`dampingRatio = 0.55f`, `stiffness = 600f`).
- Specular rim intensity pulse on active press (`borderAlpha` increases by `0.30f`).
- Smooth bounce back on release.

- [ ] **Step 1: Implement `Modifier.gelPressClickable` in `LiquidGlassModifiers.kt`**
- [ ] **Step 2: Add unit tests verifying interaction source triggers state transitions**
- [ ] **Step 3: Run unit tests**
- [ ] **Step 4: Commit changes** (`feat(ui): add gel-press tactile spring physics modifier`)

---

### Task 4: Complete Glassification of Player Screen Controls

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/player/NowPlayingScreen.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/common/Components.kt` (SeekBar)

**Details:**
1. **Top Bar:** Encase "Now Playing" album title in a floating glassy capsule pill.
2. **SeekBar:** Transform flat slider into glowing refractive glass bar that smoothly expands height from 4.dp to 8.dp during scrubbing.
3. **Player Pills:** Upgrade `PlayerPill` (speed, bedtime, EQ, lyrics) to use `liquidGlassButton` / `liquidGlass` with active glowing state.
4. **Playback Controls:** Add gel-press physics to play/pause, next, previous, shuffle, and repeat buttons.

- [ ] **Step 1: Update `SeekBar` in `Components.kt` with dynamic expansion and glow thumb**
- [ ] **Step 2: Update `NowPlayingScreen.kt` top bar, pills, and controls to use `liquidGlass` with gel-press**
- [ ] **Step 3: Run unit tests to verify no playback regressions**
- [ ] **Step 4: Commit changes** (`feat(player): glassify player controls, pills, top bar, and interactive seekbar`)

---

### Task 5: Glassification of Bottom Sheets & App Banners

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/player/PlayerSheets.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/ui/home/HomeScreen.kt`

**Details:**
1. **Modal Bottom Sheets (`QueueSheet`, `EffectsSheet`, `SleepTimerSheet`, `DevicesSheet`):** Replace opaque `0xF0121626` with authentic translucent frosted glass (`0x2AFFFFFF` with blur and specular top rim border).
2. **Home Screen Update Banner:** Ensure `availableUpdate` uses `glassyCard` instead of opaque `c.elevated`.
3. **Offline Banner:** Wrap offline mode indicator in `glassyCard`.

- [ ] **Step 1: Update `PlayerSheets.kt` `ModalBottomSheet` container styling and sheet backgrounds**
- [ ] **Step 2: Update `HomeScreen.kt` banners to use `glassyCard`**
- [ ] **Step 3: Run unit tests**
- [ ] **Step 4: Commit changes** (`feat(ui): glassify modal bottom sheets and home notification banners`)

---

### Task 6: Audio DSP Enhancements (Environmental Reverb & Crossfade)

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/playback/AudioEffects.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/playback/PlaybackService.kt`
- Modify: `spotifree-android/app/src/test/java/com/spotifree/app/AudioEffectsTest.kt`

**Details (inspired by Lelonio/Square):**
1. **Environmental Reverb:** Integrate Android's native `android.media.audiofx.EnvironmentalReverb` effect attached to ExoPlayer's `audioSessionId`, providing real acoustic spaces (Room, Hall, Plate) for the "Slowed + Reverb" preset.
2. **Crossfade:** Implement smooth volume fading (0.5s to 3s) during track changes so playback flows seamlessly like in Square and LastWave.

- [ ] **Step 1: Write unit tests in `AudioEffectsTest.kt` for reverb preset validation and crossfade timing calculations**
- [ ] **Step 2: Implement `EnvironmentalReverb` management in `AudioEffects.kt`**
- [ ] **Step 3: Implement crossfade volume interpolation in `PlaybackService.kt`**
- [ ] **Step 4: Run unit tests to confirm all 20+ tests pass**
- [ ] **Step 5: Commit changes** (`feat(audio): add native environmental reverb and track crossfade`)
