package com.spotifree.app.playback

import com.spotifree.app.util.runCatchingCancellable

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.util.Log
import com.spotifree.app.data.prefs.AppSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Equalizer (5 bands, like the previous app), reverb for the "slowed + reverb" style presets and
 * loudness normalization, attached to the player's audio session.
 */
class AudioEffects {
    private var sessionId = 0
    private var equalizer: Equalizer? = null
    private var reverb: PresetReverb? = null
    private var loudness: LoudnessEnhancer? = null
    private var last: AppSettings? = null

    fun attach(audioSessionId: Int, settings: AppSettings) {
        if (audioSessionId == 0 || audioSessionId == sessionId) {
            apply(settings)
            return
        }
        release()
        sessionId = audioSessionId
        equalizer = runCatchingCancellable { Equalizer(0, audioSessionId) }.onFailure { Log.w(TAG, "equalizer", it) }.getOrNull()
        reverb = runCatchingCancellable { PresetReverb(0, audioSessionId) }.onFailure { Log.w(TAG, "reverb", it) }.getOrNull()
        loudness = runCatchingCancellable { LoudnessEnhancer(audioSessionId) }.onFailure { Log.w(TAG, "loudness", it) }.getOrNull()
        last = null
        apply(settings)
    }

    fun apply(settings: AppSettings) {
        if (settings == last) return
        last = settings
        applyEqualizer(settings.equalizerBands)
        applyReverb(settings.effectPreset)
        runCatchingCancellable {
            loudness?.let {
                // A gentle boost evens out quiet uploads; louder masters are already near 0 dB
                it.setTargetGain(if (settings.volumeNormalization) 300 else 0)
                it.enabled = settings.volumeNormalization
            }
        }
    }

    private fun applyEqualizer(bandsDb: List<Int>) {
        val eq = equalizer ?: return
        runCatchingCancellable {
            val active = bandsDb.any { it != 0 }
            eq.enabled = active
            if (!active) return
            val range = eq.bandLevelRange // millibels [min, max]
            val targets = listOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000) // milliHertz
            for (band in 0 until eq.numberOfBands) {
                val center = eq.getCenterFreq(band.toShort())
                val nearest = targets.indices.minBy { abs(targets[it] - center) }
                val mb = (bandsDb.getOrElse(nearest) { 0 } * 100).coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), mb.toShort())
            }
        }.onFailure { Log.w(TAG, "equalizer bands", it) }
    }

    private fun applyReverb(preset: String) {
        val rv = reverb ?: return
        runCatchingCancellable {
            when (preset) {
                "slowed_reverb" -> { rv.preset = PresetReverb.PRESET_LARGEHALL; rv.enabled = true }
                "vaporwave" -> { rv.preset = PresetReverb.PRESET_PLATE; rv.enabled = true }
                "nightcore" -> { rv.preset = PresetReverb.PRESET_SMALLROOM; rv.enabled = true }
                else -> rv.enabled = false
            }
        }
    }

    fun release() {
        runCatchingCancellable { equalizer?.release() }
        runCatchingCancellable { reverb?.release() }
        runCatchingCancellable { loudness?.release() }
        equalizer = null
        reverb = null
        loudness = null
        sessionId = 0
    }

    companion object {
        private const val TAG = "AudioEffects"

        val EQ_PRESETS: Map<String, List<Int>> = mapOf(
            "flat" to listOf(0, 0, 0, 0, 0),
            "bassBoost" to listOf(6, 4, 1, 0, 0),
            "rock" to listOf(5, 3, -1, 3, 5),
            "pop" to listOf(-1, 2, 5, 2, -1),
            "vocal" to listOf(-2, 1, 5, 4, 1),
            "electronic" to listOf(6, 4, 0, 3, 5),
        )

        /** Speed and pitch of each style preset (pitch follows speed when the pitch is not preserved). */
        fun speedPitch(settings: AppSettings): Pair<Float, Float> = when (settings.effectPreset) {
            "slowed_reverb" -> 0.85f to 0.85f
            "nightcore" -> 1.25f to 1.25f
            "vaporwave" -> 0.78f to 0.78f
            else -> {
                val speed = settings.playbackSpeed.coerceIn(0.5f, 2f)
                speed to if (settings.preservePitch) 1f else speed
            }
        }

        fun roundSpeed(v: Float) = (v * 100).roundToInt() / 100f
    }
}
