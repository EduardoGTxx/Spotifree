package com.spotifree.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.spotifree_core_ffi.AudioCacheBridge

class RustAudioCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writeAndReadChunkRoundTrip() {
        val cache = AudioCacheBridge(folder.root.absolutePath, 10 * 1024 * 1024L)
        val sampleData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertFalse(cache.isCached("track_123"))

        cache.writeChunk("track_123", 0L, sampleData)
        assertTrue(cache.isCached("track_123"))

        val readData = cache.readRange("track_123", 0L, 8)
        assertArrayEquals(sampleData, readData)
    }
}
