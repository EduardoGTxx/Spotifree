package com.spotifree.app

import com.spotifree.app.core.SpotifreeCoreManager
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSystemIntegrationTest {
    @Test
    fun coreManagerInitializesAllSubsystemsSuccessfully() {
        val available = SpotifreeCoreManager.isAvailable()
        assertTrue("Rust Core should load native libraries cleanly and report availability", available)

        val ver = SpotifreeCoreManager.version()
        assertTrue("Version must start with 0.1, got: $ver", ver.startsWith("0.1"))
    }
}
