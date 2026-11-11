package com.spotifree.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.spotifree_core_ffi.AudioProcessorBridge

class RustAudioDspTest {
    @Test
    fun audioProcessorModifiesPcmWithEqualizer() {
        val proc = AudioProcessorBridge(44100, 2)
        proc.setEqualizerBand(0, 12.0f) // +12 dB on bass band

        // 44100Hz audio sine-like signal at 60Hz (bass)
        val input = ByteArray(1024) { i ->
            val sample = (Math.sin(2.0 * Math.PI * 60.0 * (i / 2) / 44100.0) * 10000.0).toInt().toShort()
            if (i % 2 == 0) (sample.toInt() and 0xFF).toByte() else ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val output = proc.processPcm(input)
        assertEquals("Output size must match input size", input.size, output.size)
        // With +12dB bass boost, output energy should be significantly different from input
        assertNotEquals(input[50], output[50])
    }

    @Test
    fun audioProcessorProducesReverbTailAcrossDelayWindow() {
        val proc = AudioProcessorBridge(44100, 2)
        proc.setReverbEnabled(true)
        proc.setReverbRoomSize(0.85f)

        // 16384 bytes (~8192 samples). Impulse at the beginning (first 4 samples), silence everywhere else
        val impulse = ByteArray(16384) { if (it in 0..3) 127 else 0 }
        val output = proc.processPcm(impulse)

        assertEquals("Output size must match input size", impulse.size, output.size)
        // Check after sample 1500 (offset 3000 bytes) where Schroeder comb echo is active
        val nonZeroSamplesAfterDelay = (3000 until 6000).any { output[it] != 0.toByte() }
        assertTrue("Reverb should produce wet echo reflections after comb delay window", nonZeroSamplesAfterDelay)
    }

    @Test
    fun audioProcessorPassesThroughPcmWhenDisabled() {
        val proc = AudioProcessorBridge(44100, 2)
        proc.setReverbEnabled(false)

        val input = byteArrayOf(10, 20, 30, 40)
        val output = proc.processPcm(input)
        org.junit.Assert.assertArrayEquals(input, output)
    }
}
