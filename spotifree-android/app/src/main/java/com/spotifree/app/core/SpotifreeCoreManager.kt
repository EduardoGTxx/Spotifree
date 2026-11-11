package com.spotifree.app.core

import android.content.Context
import android.util.Log
import uniffi.spotifree_core_ffi.AudioCacheBridge
import uniffi.spotifree_core_ffi.AudioProcessorBridge
import java.io.File

object SpotifreeCoreManager {
    private const val TAG = "SpotifreeCore"
    private var isNativeLoaded = false
    var audioCache: AudioCacheBridge? = null
        private set
    var audioProcessor: AudioProcessorBridge? = null
        private set

    fun initialize(context: Context) {
        if (isNativeLoaded) return
        try {
            System.loadLibrary("spotifree_core_ffi")
            isNativeLoaded = true
            Log.i(TAG, "Spotifree Native Rust Core loaded successfully (version: ${version()})")

            val cacheDir = File(context.cacheDir, "spotifree_native_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            audioCache = runCatching { AudioCacheBridge(cacheDir.absolutePath, 500 * 1024 * 1024L) }.getOrNull()
            audioProcessor = runCatching { AudioProcessorBridge(44100, 2) }.getOrNull()
        } catch (t: Throwable) {
            isNativeLoaded = false
            Log.w(TAG, "Spotifree Native Rust Core not available on this ABI, running JVM fallback: ${t.message}")
        }
    }

    fun isAvailable(): Boolean = isNativeLoaded || runCatching { uniffi.spotifree_core_ffi.spotifreeCoreVersion() }.isSuccess

    fun version(): String = runCatching { uniffi.spotifree_core_ffi.spotifreeCoreVersion() }.getOrDefault("0.0.0-fallback")
}
