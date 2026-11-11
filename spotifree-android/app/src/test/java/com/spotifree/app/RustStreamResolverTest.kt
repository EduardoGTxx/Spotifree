package com.spotifree.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.spotifree_core_network.NativeStreamInfo

class RustStreamResolverTest {
    @Test
    fun nativeStreamInfoPropertiesAreExposedToKotlin() {
        val stream = NativeStreamInfo(
            videoId = "dQw4w9WgXcQ",
            url = "https://googlevideo.com/videoplayback?id=123",
            mimeType = "audio/mp4",
            itag = 140,
            bitrate = 128000,
            durationSeconds = 212L
        )
        assertNotNull(stream)
        assertTrue(stream.url.startsWith("https://"))
        org.junit.Assert.assertEquals("dQw4w9WgXcQ", stream.videoId)
        org.junit.Assert.assertEquals(140, stream.itag)
    }

    @Test
    fun resolveStreamUrlThrowsExpectedExceptionOnEmptyId() = runBlocking {
        var errorThrown = false
        try {
            uniffi.spotifree_core_ffi.resolveStreamUrl("")
        } catch (e: uniffi.spotifree_core_ffi.SpotifreeException) {
            errorThrown = true
        }
        assertTrue("Empty video ID should trigger SpotifreeException", errorThrown)
    }
}
