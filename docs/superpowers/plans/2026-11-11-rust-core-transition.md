# Rust Core + Kotlin UI Architecture Transition Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transition Spotifree from a pure Kotlin/JVM data & audio pipeline to a high-performance hybrid architecture where networking, streaming cipher deciphering, chunked audio caching, and low-latency audio DSP are powered by a native Rust core (`spotifree-core`) using Mozilla UniFFI, while retaining 100% of the Jetpack Compose Material 3 UI and AndroidX Media3 platform integration.

**Architecture:** Spotifree adopts the industry-standard "Rust Engine / Kotlin Shell" architecture (similar to Spotify and Firefox). Computationally heavy, network-intensive, and real-time operations (InnerTube API extraction, signature deciphering, offline encrypted caching, parametric EQ, and reverb DSP) live in a modular Rust workspace compiled to native `.so` binaries across Android target ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`). Mozilla UniFFI generates type-safe, thread-safe Kotlin bindings directly from Rust traits and structs, exposing suspend functions and coroutines to the existing Kotlin ViewModels, `SpotifreeGraph`, and `PlaybackService`.

**Tech Stack:**
- **Rust Toolchain:** Rust 2021/2024 edition, `cargo-ndk`, `uniffi` 0.28+, `tokio` (async runtime), `reqwest` (HTTP/2 network client), `serde` & `serde_json`.
- **Audio DSP & Storage:** `symphonia` (audio container & codec decoding), SIMD biquad filters (parametric EQ), Schroeder algorithmic reverb, `redb` / `rusqlite` (embedded zero-GC cache index).
- **Android / Kotlin Shell:** Kotlin 2.2+, Jetpack Compose (BOM 2025+), AndroidX Media3 (ExoPlayer 1.5+), Gradle NDK integration (`cargo-ndk-android`).

**Spec:** Architectural design for modular migration from pure JVM data sources (`StreamResolver.kt`, `AudioEffects.kt`, `SwitchingDataSource.kt`) to UniFFI-bound native crates.

---

## Global Constraints

- Minimum Android SDK remains API 29 (Android 10); Target SDK is 36.
- Supported Android ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- Zero manual JNI: All cross-language interfaces must use Mozilla UniFFI (`uniffi::export` proc-macros).
- All asynchronous Rust functions must project to Kotlin as `suspend` functions compatible with Kotlin Coroutines.
- All errors returned across the FFI boundary must use typed `Result<T, SpotifreeError>` mapped to Kotlin `SpotifreeException` without crashing the process.
- The Jetpack Compose UI layer, `LocalSpotifreeColors`, themes, and Android `MediaSession` integration must remain 100% intact.
- Every Rust crate must achieve 100% passing tests via `cargo test`, and Android unit tests must pass 100% via `./gradlew testDebugUnitTest`.

---

## Review Focus

1. **Cross-Architecture ABI Compilation:** Ensure all native crates compile cleanly for 32-bit ARM (`armeabi-v7a`) as well as 64-bit ARM/x86 without alignment or pointer width bugs.
2. **Panic Boundary Containment:** Ensure any unexpected panic inside Rust async worker threads is intercepted and converted to a safe `SpotifreeError::Internal` before reaching JNI, preventing app crashes.
3. **FFI Memory & Handle Management:** Ensure Kotlin wrappers implement `AutoCloseable` or rely on UniFFI's Arc pointers to avoid native memory leaks when streams or search sessions are disposed.
4. **Coroutine Cancellation Propagation:** Ensure that cancelling a Kotlin `CoroutineScope` (e.g., when skipping to the next song) cleanly cancels the underlying Rust `tokio::task` and releases network sockets.
5. **Real-time Audio Buffer Safety:** In the audio DSP pipeline, ensure audio render callbacks never allocate heap memory or execute blocking I/O on the real-time audio thread.

---

## File Structure & Module Map

```
Spotifree/
├── spotifree-core/                                # Root Rust Workspace
│   ├── Cargo.toml                                 # Workspace manifest
│   ├── spotifree-core-ffi/                        # UniFFI bridge crate (cdylib + rlib)
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   │   ├── lib.rs                             # uniffi::setup_scaffolding!() & exports
│   │   │   ├── error.rs                           # SpotifreeError enum
│   │   │   └── types.rs                           # Shared cross-boundary models
│   ├── spotifree-core-network/                    # InnerTube scraper & stream resolver
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── innertube.rs                       # YouTube Music API & cipher deciphering
│   │   │   └── models.rs
│   ├── spotifree-core-storage/                    # Chunked audio cache & metadata index
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── cache.rs                           # LRU chunked disk cache
│   │   │   └── index.rs                           # redb-backed catalog storage
│   └── spotifree-core-audio/                      # High-performance DSP engine
│       ├── Cargo.toml
│       ├── src/
│       │   ├── lib.rs
│       │   ├── equalizer.rs                       # SIMD biquad filters
│       │   ├── reverb.rs                          # Algorithmic Schroeder/Moorer reverb
│       │   └── crossfade.rs                       # Equal-power crossfade mixer
│
└── spotifree-android/                             # Android application module
    ├── app/
    │   ├── build.gradle.kts                       # Cargo NDK task configuration
    │   └── src/
    │       ├── main/
    │       │   ├── jniLibs/                       # Generated .so native binaries per ABI
    │       │   └── java/com/spotifree/app/
    │       │       ├── core/                      # UniFFI generated & wrapper bindings
    │       │       │   ├── SpotifreeCore.kt       # Core entry point & lifecycle
    │       │       │   └── uniffi/spotifree/      # Generated UniFFI code
    │       │       ├── data/remote/
    │       │       │   └── StreamResolver.kt      # Refactored to delegate to Rust core
    │       │       └── playback/
    │       │           ├── AudioEffects.kt        # Refactored to bind Rust DSP
    │       │           └── PlaybackService.kt     # Uses Rust audio pipeline
    └── build.gradle.kts
```

---

## Tasks

### Task 1: Rust Workspace Scaffolding & UniFFI Cargo-NDK Toolchain

**Files:**
- Create: `spotifree-core/Cargo.toml`
- Create: `spotifree-core/spotifree-core-ffi/Cargo.toml`
- Create: `spotifree-core/spotifree-core-ffi/src/lib.rs`
- Create: `spotifree-core/spotifree-core-ffi/src/error.rs`
- Modify: `spotifree-android/app/build.gradle.kts`
- Test: `spotifree-android/app/src/test/java/com/spotifree/app/SpotifreeCoreFfiTest.kt`

**Interfaces:**
- Produces:
  ```rust
  #[uniffi::export]
  pub fn spotifree_core_version() -> String;

  #[uniffi::export]
  pub fn spotifree_core_ping(payload: String) -> Result<String, SpotifreeError>;
  ```
  And corresponding Kotlin binding:
  ```kotlin
  fun spotifreeCoreVersion(): String
  fun spotifreeCorePing(payload: String): String
  ```

- [ ] **Step 1: Write the failing unit test in Kotlin**

```kotlin
package com.spotifree.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifreeCoreFfiTest {
    @Test
    fun pingReturnsEchoFromRust() {
        val version = uniffi.spotifree.spotifreeCoreVersion()
        assertTrue(version.startsWith("0.1"))
        val echo = uniffi.spotifree.spotifreeCorePing("hello from compose")
        assertEquals("echo: hello from compose", echo)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.SpotifreeCoreFfiTest"`
Expected: FAIL with compilation error (uniffi package not found).

- [ ] **Step 3: Create the Rust workspace and configure `spotifree-core-ffi` with UniFFI**

1. Create `spotifree-core/Cargo.toml` configuring the workspace members.
2. In `spotifree-core/spotifree-core-ffi/Cargo.toml`, set `crate-type = ["cdylib", "rlib"]`, and add `uniffi = { version = "0.28", features = ["tokio"] }`.
3. Implement `spotifree_core_version()` and `spotifree_core_ping()` in `spotifree-core-ffi/src/lib.rs` using `uniffi::setup_scaffolding!()`.
4. Configure `spotifree-android/app/build.gradle.kts` with `cargo-ndk` to build and copy native libraries into `jniLibs` prior to assembling or running tests.
5. Generate Kotlin UniFFI bindings using `uniffi-bindgen`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.SpotifreeCoreFfiTest"`
Expected: PASS (1 test executed, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add spotifree-core spotifree-android/app/build.gradle.kts spotifree-android/app/src/test/java/com/spotifree/app/SpotifreeCoreFfiTest.kt
git commit -m "feat(core): scaffold Rust workspace and UniFFI Android toolchain"
```

---

### Task 2: Native InnerTube Engine & Stream Resolver (`spotifree-core-network`)

**Files:**
- Create: `spotifree-core/spotifree-core-network/Cargo.toml`
- Create: `spotifree-core/spotifree-core-network/src/lib.rs`
- Create: `spotifree-core/spotifree-core-network/src/innertube.rs`
- Create: `spotifree-core/spotifree-core-network/src/cipher.rs`
- Modify: `spotifree-core/spotifree-core-ffi/src/lib.rs`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/data/remote/StreamResolver.kt`
- Test: `spotifree-core/spotifree-core-network/tests/innertube_test.rs`
- Test: `spotifree-android/app/src/test/java/com/spotifree/app/RustStreamResolverTest.kt`

**Interfaces:**
- Consumes: `spotifree-core-ffi` error handling types.
- Produces:
  ```rust
  #[derive(uniffi::Record)]
  pub struct NativeStreamInfo {
      pub url: String,
      pub mime_type: String,
      pub bitrate: i32,
      pub duration_seconds: i64,
  }

  #[uniffi::export(async_runtime = "tokio")]
  pub async fn resolve_stream_url(video_id: String) -> Result<NativeStreamInfo, SpotifreeError>;
  ```
  Mapped in Kotlin to:
  ```kotlin
  suspend fun resolveStreamUrl(videoId: String): NativeStreamInfo
  ```

- [ ] **Step 1: Write the failing Rust unit test for cipher extraction and Kotlin bridge test**

In `spotifree-android/app/src/test/java/com/spotifree/app/RustStreamResolverTest.kt`:
```kotlin
package com.spotifree.app

import com.spotifree.app.data.remote.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustStreamResolverTest {
    @Test
    fun streamResolverUsesRustCoreWhenAvailable() = runBlocking {
        val resolver = StreamResolver()
        val stream = resolver.resolveStreamInfo("dQw4w9WgXcQ")
        assertNotNull(stream)
        assertTrue(stream.url.startsWith("http"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.RustStreamResolverTest"`
Expected: FAIL (method missing or returning legacy mock).

- [ ] **Step 3: Implement InnerTube client & cipher deciphering in Rust**

1. Implement `cipher.rs` in `spotifree-core-network` with regex-based transformation function extraction (swap, slice, reverse) to decipher throttling signatures (`s=` and `sp=`).
2. Implement HTTP client in `innertube.rs` targeting `https://music.youtube.com/youtubei/v1/player` with client headers (`ANDROID_MUSIC` or `WEB_REMIX`).
3. Export `resolve_stream_url` in `spotifree-core-ffi`.
4. Update `StreamResolver.kt` in Kotlin to prioritize calling `uniffi.spotifree.resolveStreamUrl(videoId)` with seamless fallback to `NewPipeDownloader` if FFI fails.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --package spotifree-core-network`
Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.RustStreamResolverTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spotifree-core/spotifree-core-network spotifree-core/spotifree-core-ffi spotifree-android/app/src/main/java/com/spotifree/app/data/remote/StreamResolver.kt
git commit -m "feat(network): implement native InnerTube streaming engine in Rust"
```

---

### Task 3: Chunked Audio Cache & Local Storage Engine (`spotifree-core-storage`)

**Files:**
- Create: `spotifree-core/spotifree-core-storage/Cargo.toml`
- Create: `spotifree-core/spotifree-core-storage/src/lib.rs`
- Create: `spotifree-core/spotifree-core-storage/src/cache.rs`
- Create: `spotifree-core/spotifree-core-storage/src/index.rs`
- Modify: `spotifree-core/spotifree-core-ffi/src/lib.rs`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/playback/SwitchingDataSource.kt`
- Test: `spotifree-core/spotifree-core-storage/tests/cache_test.rs`
- Test: `spotifree-android/app/src/test/java/com/spotifree/app/RustAudioCacheTest.kt`

**Interfaces:**
- Consumes: File system storage directory from Android context.
- Produces:
  ```rust
  #[uniffi::export]
  pub struct NativeAudioCache { ... }

  #[uniffi::export]
  impl NativeAudioCache {
      #[uniffi::constructor]
      pub fn new(cache_dir: String, max_bytes: i64) -> Result<Arc<Self>, SpotifreeError>;
      pub fn write_chunk(&self, key: String, offset: i64, data: Vec<u8>) -> Result<(), SpotifreeError>;
      pub fn read_range(&self, key: String, offset: i64, length: i32) -> Result<Option<Vec<u8>>, SpotifreeError>;
      pub fn is_fully_cached(&self, key: String) -> bool;
  }
  ```

- [ ] **Step 1: Write the failing Kotlin unit test for audio cache**

```kotlin
package com.spotifree.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RustAudioCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writeAndReadChunkRoundTrip() {
        val cache = uniffi.spotifree.NativeAudioCache(folder.root.absolutePath, 10 * 1024 * 1024)
        val sampleData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        cache.writeChunk("track_123", 0L, sampleData)
        val readData = cache.readRange("track_123", 0L, 8)
        assertArrayEquals(sampleData, readData)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.RustAudioCacheTest"`
Expected: FAIL (`NativeAudioCache` not found).

- [ ] **Step 3: Implement chunked storage and indexing in Rust**

1. Create `spotifree-core-storage` with `redb` for key-offset-range indices and memory-mapped file slices.
2. Implement LRU eviction when total disk usage exceeds `max_bytes`.
3. Export `NativeAudioCache` through UniFFI in `spotifree-core-ffi`.
4. Connect `SwitchingDataSource.kt` to query `NativeAudioCache` before opening network sockets, eliminating duplicate requests and providing zero-copy offline playback.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --package spotifree-core-storage`
Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.RustAudioCacheTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spotifree-core/spotifree-core-storage spotifree-core/spotifree-core-ffi spotifree-android/app/src/main/java/com/spotifree/app/playback/SwitchingDataSource.kt
git commit -m "feat(storage): implement native chunked audio cache with LRU eviction"
```

---

### Task 4: High-Performance Audio DSP Engine (`spotifree-core-audio`)

**Files:**
- Create: `spotifree-core/spotifree-core-audio/Cargo.toml`
- Create: `spotifree-core/spotifree-core-audio/src/lib.rs`
- Create: `spotifree-core/spotifree-core-audio/src/equalizer.rs`
- Create: `spotifree-core/spotifree-core-audio/src/reverb.rs`
- Create: `spotifree-core/spotifree-core-audio/src/crossfade.rs`
- Modify: `spotifree-core/spotifree-core-ffi/src/lib.rs`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/playback/AudioEffects.kt`
- Test: `spotifree-core/spotifree-core-audio/tests/dsp_test.rs`
- Test: `spotifree-android/app/src/test/java/com/spotifree/app/RustAudioDspTest.kt`

**Interfaces:**
- Consumes: 16-bit or 32-bit float PCM audio buffers from Media3 ExoPlayer AudioProcessor.
- Produces:
  ```rust
  #[uniffi::export]
  pub struct NativeAudioProcessor { ... }

  #[uniffi::export]
  impl NativeAudioProcessor {
      #[uniffi::constructor]
      pub fn new(sample_rate: i32, channels: i32) -> Arc<Self>;
      pub fn set_reverb_enabled(&self, enabled: Boolean);
      pub fn set_reverb_room_size(&self, size: f32);
      pub fn set_equalizer_band(&self, band: i32, gain_db: f32);
      pub fn process_pcm(&self, input_pcm: Vec<u8>) -> Vec<u8>;
  }
  ```

- [ ] **Step 1: Write the failing Kotlin unit test for audio DSP processing**

```kotlin
package com.spotifree.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RustAudioDspTest {
    @Test
    fun processingPcmWithReverbModifiesSampleBuffer() {
        val processor = uniffi.spotifree.NativeAudioProcessor(44100, 2)
        processor.setReverbEnabled(true)
        processor.setReverbRoomSize(0.8f)
        val impulse = ByteArray(1024) { if (it in 0..3) 127 else 0 }
        val output = processor.processPcm(impulse)
        assertEquals(impulse.size, output.size)
        assertNotEquals(impulse[100], output[100])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.RustAudioDspTest"`
Expected: FAIL (`NativeAudioProcessor` not found).

- [ ] **Step 3: Implement real-time SIMD Biquad EQ & Algorithmic Reverb in Rust**

1. Implement biquad peaking/shelving IIR filters with direct form II in `equalizer.rs`.
2. Implement Schroeder reverb network (4 parallel feedback comb filters + 2 series allpass filters) in `reverb.rs`.
3. Implement continuous power crossfade math in `crossfade.rs`.
4. Export `NativeAudioProcessor` via UniFFI.
5. In `AudioEffects.kt`, implement an AndroidX Media3 `AudioProcessor` that delegates PCM buffer transformation to `NativeAudioProcessor`, bypassing platform-dependent `android.media.audiofx` bugs.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --package spotifree-core-audio`
Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.RustAudioDspTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spotifree-core/spotifree-core-audio spotifree-core/spotifree-core-ffi spotifree-android/app/src/main/java/com/spotifree/app/playback/AudioEffects.kt
git commit -m "feat(audio): implement native SIMD equalizer and reverb DSP processor in Rust"
```

---

### Task 5: End-to-End Kotlin Shell Wiring, Fallbacks & Full Build Verification

**Files:**
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/SpotifreeRoot.kt`
- Modify: `spotifree-android/app/src/main/java/com/spotifree/app/playback/PlaybackService.kt`
- Create: `spotifree-android/app/src/main/java/com/spotifree/app/core/SpotifreeCoreManager.kt`
- Modify: `.github/workflows/release-android.yml`
- Test: `spotifree-android/app/src/test/java/com/spotifree/app/FullSystemIntegrationTest.kt`

**Interfaces:**
- Consumes: All services from Tasks 1-4.
- Produces: Integrated Android application with verified native binary packaging.

- [ ] **Step 1: Write full system integration test in Kotlin**

```kotlin
package com.spotifree.app

import com.spotifree.app.core.SpotifreeCoreManager
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSystemIntegrationTest {
    @Test
    fun coreManagerInitializesAllSubsystemsSuccessfully() {
        val initialized = SpotifreeCoreManager.isAvailable()
        assertTrue("Rust Core should load native libraries cleanly", initialized)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd /c "cd /d spotifree-android && gradlew.bat testDebugUnitTest --tests com.spotifree.app.FullSystemIntegrationTest"`
Expected: FAIL (`SpotifreeCoreManager` not defined).

- [ ] **Step 3: Implement `SpotifreeCoreManager` and update CI/CD workflow**

1. Create `SpotifreeCoreManager.kt` to load `System.loadLibrary("spotifree_core_ffi")` at startup in `SpotifreeRoot.kt` / `Application.onCreate()`, with graceful degraded fallback to pure Kotlin if native binary is absent.
2. In `PlaybackService.kt`, integrate the `NativeAudioProcessor` into ExoPlayer's `RenderersFactory`.
3. Update `.github/workflows/release-android.yml` to install the Rust toolchain (`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`) and `cargo-ndk` before assembling the release APK.

- [ ] **Step 4: Run complete verification suite**

Run: `cmd /c "cd /d spotifree-android && gradlew.bat assembleDebug testDebugUnitTest"`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add spotifree-android/app/src/main/java/com/spotifree/app/core/SpotifreeCoreManager.kt .github/workflows/release-android.yml spotifree-android/app/src/main/java/com/spotifree/app/playback/PlaybackService.kt
git commit -m "feat(integration): wire Rust core lifecycle into Application and CI pipeline"
```
