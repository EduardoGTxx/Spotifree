package com.spotifree.app

import com.spotifree.app.data.lyrics.Romanizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.spotifree_core_ffi.calculateGainAdjustment
import uniffi.spotifree_core_ffi.calculateLoudnessLufs
import uniffi.spotifree_core_ffi.fuzzyMatchTrack
import uniffi.spotifree_core_ffi.generateAudioFingerprint
import uniffi.spotifree_core_ffi.romanizeText

class RustCoreExpansionTest {

    @Test
    fun testRomanizerDelegation() {
        val korean = Romanizer.romanize("안녕하세요")
        assertEquals("Annyeonghaseyo", korean)

        val japanese = Romanizer.romanize("ありがとう")
        assertEquals("Arigatou", japanese)

        val cyrillic = Romanizer.romanize("Привет")
        assertEquals("Privet", cyrillic)

        val greek = Romanizer.romanize("Καλημέρα")
        assertEquals("Kalimera", greek)

        // Latin lyrics should return null so no duplicate line is shown
        val latin = Romanizer.romanize("Hello World")
        assertNull(latin)
    }

    @Test
    fun testNativeRomanizeDirectExport() {
        val res = romanizeText("한글")
        assertEquals("Hangeul", res)
    }

    @Test
    fun testFuzzyMatchTrackScoring() {
        val highMatch = fuzzyMatchTrack(
            targetArtist = "Coldplay",
            targetTitle = "Yellow",
            targetDurationSec = 269,
            candArtist = "Coldplay",
            candTitle = "Yellow (Official Music Video)",
            candDurationSec = 268,
        )
        assertTrue("High match should score above 75, got $highMatch", highMatch >= 75.0f)

        val lowMatch = fuzzyMatchTrack(
            targetArtist = "Adele",
            targetTitle = "Hello",
            targetDurationSec = 295,
            candArtist = "Lionel Richie",
            candTitle = "Hello",
            candDurationSec = 245,
        )
        assertTrue("Low match should score below 60, got $lowMatch", lowMatch < 60.0f)
    }

    @Test
    fun testLoudnessAndGainCalculation() {
        // 44.1kHz silence
        val silence = ByteArray(1000) { 0 }
        val lufs = calculateLoudnessLufs(silence, 44100)
        assertEquals(-70.0f, lufs, 0.1f)

        // Target -14 LUFS, current -8 LUFS -> needs -6 dB gain
        val gain = calculateGainAdjustment(-14.0f, -8.0f)
        assertEquals(-6.0f, gain, 0.1f)
    }

    @Test
    fun testAudioFingerprintGeneration() {
        // Generate simulated 1s PCM sine wave audio
        val sampleRate = 44100
        val pcm = ByteArray(sampleRate * 2) { i ->
            val sampleIndex = i / 2
            val t = sampleIndex.toDouble() / sampleRate.toDouble()
            val sampleVal = (Math.sin(2.0 * Math.PI * 440.0 * t) * 20000.0).toInt().toShort()
            if (i % 2 == 0) (sampleVal.toInt() and 0xFF).toByte() else ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        val hashes = generateAudioFingerprint(pcm, sampleRate)
        assertNotNull(hashes)
        assertTrue("Fingerprint hashes should not be empty", hashes.isNotEmpty())
        assertEquals("Each hash should be 8 hex characters", 8, hashes[0].length)
    }
}
