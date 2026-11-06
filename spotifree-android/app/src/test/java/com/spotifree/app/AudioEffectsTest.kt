package com.spotifree.app

import com.spotifree.app.data.prefs.AppSettings
import com.spotifree.app.playback.AudioEffects
import com.spotifree.app.playback.ReverbProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive unit test suite for AudioEffects, EnvironmentalReverb profiles,
 * speed/pitch calculations, equal-power crossfade curves, and lifecycle safety.
 */
class AudioEffectsTest {

    // 1. Equalizer Presets Tests
    @Test
    fun eqPresetsContainsExpectedKeys() {
        val presets = AudioEffects.EQ_PRESETS
        val expected = listOf("flat", "bassBoost", "rock", "pop", "vocal", "electronic")
        for (key in expected) {
            assertTrue("Expected preset $key in EQ_PRESETS", presets.containsKey(key))
        }
    }

    @Test
    fun eqPresetBandCountsAreAll5() {
        for ((name, bands) in AudioEffects.EQ_PRESETS) {
            assertEquals("Preset $name should have exactly 5 bands", 5, bands.size)
        }
    }

    @Test
    fun eqPresetValuesWithinExpectedDecibelBounds() {
        for ((name, bands) in AudioEffects.EQ_PRESETS) {
            for (gain in bands) {
                assertTrue("Gain $gain dB in $name should be within -12..12 dB", gain in -12..12)
            }
        }
    }

    @Test
    fun eqFlatPresetHasAllZeros() {
        val flat = AudioEffects.EQ_PRESETS["flat"]
        assertNotNull(flat)
        assertTrue(flat!!.all { it == 0 })
    }

    // 2. Speed and Pitch Calculations
    @Test
    fun speedPitchNormalDefaultsToOne() {
        val settings = AppSettings(effectPreset = "normal", playbackSpeed = 1.0f)
        val (speed, pitch) = AudioEffects.speedPitch(settings)
        assertEquals(1.0f, speed, 0.001f)
        assertEquals(1.0f, pitch, 0.001f)
    }

    @Test
    fun speedPitchSlowedReverbIsCorrect() {
        val settings = AppSettings(effectPreset = "slowed_reverb")
        val (speed, pitch) = AudioEffects.speedPitch(settings)
        assertEquals(0.85f, speed, 0.001f)
        assertEquals(0.85f, pitch, 0.001f)
    }

    @Test
    fun speedPitchNightcoreIsCorrect() {
        val settings = AppSettings(effectPreset = "nightcore")
        val (speed, pitch) = AudioEffects.speedPitch(settings)
        assertEquals(1.25f, speed, 0.001f)
        assertEquals(1.25f, pitch, 0.001f)
    }

    @Test
    fun speedPitchVaporwaveIsCorrect() {
        val settings = AppSettings(effectPreset = "vaporwave")
        val (speed, pitch) = AudioEffects.speedPitch(settings)
        assertEquals(0.78f, speed, 0.001f)
        assertEquals(0.78f, pitch, 0.001f)
    }

    @Test
    fun speedPitchPreservePitchMaintainsOne() {
        val settings = AppSettings(playbackSpeed = 1.5f, preservePitch = true, effectPreset = "normal")
        val (speed, pitch) = AudioEffects.speedPitch(settings)
        assertEquals(1.5f, speed, 0.001f)
        assertEquals(1.0f, pitch, 0.001f)
    }

    @Test
    fun speedPitchPreservePitchFalseMatchesSpeed() {
        val settings = AppSettings(playbackSpeed = 1.5f, preservePitch = false, effectPreset = "normal")
        val (speed, pitch) = AudioEffects.speedPitch(settings)
        assertEquals(1.5f, speed, 0.001f)
        assertEquals(1.5f, pitch, 0.001f)
    }

    @Test
    fun speedPitchClampsExtremePlaybackSpeed() {
        val slow = AppSettings(playbackSpeed = 0.2f, preservePitch = false, effectPreset = "normal")
        val (slowSpeed, _) = AudioEffects.speedPitch(slow)
        assertEquals(0.5f, slowSpeed, 0.001f)

        val fast = AppSettings(playbackSpeed = 3.5f, preservePitch = false, effectPreset = "normal")
        val (fastSpeed, _) = AudioEffects.speedPitch(fast)
        assertEquals(2.0f, fastSpeed, 0.001f)
    }

    @Test
    fun roundSpeedRoundsToTwoDecimals() {
        assertEquals(1.25f, AudioEffects.roundSpeed(1.254f), 0.001f)
        assertEquals(1.26f, AudioEffects.roundSpeed(1.256f), 0.001f)
    }

    // 3. Environmental Reverb Profiles
    @Test
    fun reverbProfileSlowedReverbHasCorrectDecay() {
        val profile = AudioEffects.getReverbProfile("slowed_reverb")
        assertNotNull(profile)
        assertEquals(2800, profile!!.decayTimeMs)
        assertEquals((-1000).toShort(), profile.roomLevel)
        assertEquals((-1200).toShort(), profile.reflectionsLevel)
    }

    @Test
    fun reverbProfileVaporwaveHasCorrectDecay() {
        val profile = AudioEffects.getReverbProfile("vaporwave")
        assertNotNull(profile)
        assertEquals(1800, profile!!.decayTimeMs)
        assertEquals((-1200).toShort(), profile.roomLevel)
        assertEquals((-1000).toShort(), profile.reflectionsLevel)
    }

    @Test
    fun reverbProfileNightcoreHasCorrectDecay() {
        val profile = AudioEffects.getReverbProfile("nightcore")
        assertNotNull(profile)
        assertEquals(800, profile!!.decayTimeMs)
        assertEquals((-1500).toShort(), profile.roomLevel)
        assertEquals((-2000).toShort(), profile.reflectionsLevel)
    }

    @Test
    fun reverbProfileNormalReturnsNull() {
        assertNull(AudioEffects.getReverbProfile("normal"))
        assertNull(AudioEffects.getReverbProfile("unknown_style"))
    }

    // 4. Volume Crossfade Timing & Curves
    @Test
    fun crossfadeZeroSecondsReturnsFullVolume() {
        val vol = AudioEffects.calculateCrossfadeVolume(
            positionMs = 500,
            durationMs = 100_000,
            crossfadeSec = 0,
            isNextTrack = false
        )
        assertEquals(1.0f, vol, 0.001f)
    }

    @Test
    fun crossfadeLinearFadeInCalculatesCorrectGradient() {
        val vol = AudioEffects.calculateCrossfadeVolume(
            positionMs = 1500,
            durationMs = 100_000,
            crossfadeSec = 3,
            isNextTrack = true,
            useEqualPower = false
        )
        assertEquals(0.5f, vol, 0.01f)
    }

    @Test
    fun crossfadeLinearFadeOutCalculatesCorrectGradient() {
        val vol = AudioEffects.calculateCrossfadeVolume(
            positionMs = 98_500,
            durationMs = 100_000,
            crossfadeSec = 3,
            isNextTrack = false,
            useEqualPower = false
        )
        assertEquals(0.5f, vol, 0.01f)
    }

    @Test
    fun crossfadeEqualPowerMaintainsPerceivedAcousticEnergy() {
        val vol = AudioEffects.calculateCrossfadeVolume(
            positionMs = 1500,
            durationMs = 100_000,
            crossfadeSec = 3,
            isNextTrack = true,
            useEqualPower = true
        )
        // Equal power at 50% linear progress: sin(0.5 * PI / 2) = sin(PI/4) ≈ 0.7071
        assertEquals(0.7071f, vol, 0.01f)
    }

    @Test
    fun crossfadeBoundsAtStartAndEndAreClamped() {
        val startVol = AudioEffects.calculateCrossfadeVolume(0, 100_000, 3, isNextTrack = true, useEqualPower = false)
        assertEquals(0.0f, startVol, 0.001f)

        val completedVol = AudioEffects.calculateCrossfadeVolume(3000, 100_000, 3, isNextTrack = true, useEqualPower = false)
        assertEquals(1.0f, completedVol, 0.001f)
    }

    // 5. Lifecycle and Resilience Tests
    @Test
    fun audioEffectsAttachWithZeroSessionIdIsSafe() {
        val effects = AudioEffects()
        effects.attach(0, AppSettings())
        effects.release()
    }

    @Test
    fun audioEffectsReleaseResetsStateSafely() {
        val effects = AudioEffects()
        effects.release()
        effects.release() // Duplicate release must not throw
    }
}
