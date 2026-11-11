package com.spotifree.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.spotifree_core_ffi.spotifreeCorePing
import uniffi.spotifree_core_ffi.spotifreeCoreVersion

class SpotifreeCoreFfiTest {
    @Test
    fun pingReturnsEchoFromRust() {
        val version = spotifreeCoreVersion()
        assertTrue("Version should start with 0.1, got: $version", version.startsWith("0.1"))
        val echo = spotifreeCorePing("hello from compose")
        assertEquals("echo: hello from compose", echo)
    }
}
