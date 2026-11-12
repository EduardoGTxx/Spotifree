package com.spotifree.app.playback

import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.util.Log
import com.spotifree.app.data.prefs.AppSettings
import com.spotifree.app.util.runCatchingCancellable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Acoustic environment configuration for native EnvironmentalReverb.
 */
data class ReverbProfile(
    val roomLevel: Short,              // Millibels (-9000..0)
    val roomHFLevel: Short = 0,        // High frequency attenuation in mB (-9000..0)
    val decayTimeMs: Int,              // Decay time in milliseconds (100..20000)
    val decayHFRatio: Short = 1000,    // High frequency decay ratio (100..2000)
    val reflectionsLevel: Short,       // Early reflections level in mB (-9000..1000)
    val reflectionsDelayMs: Int = 20,  // Early reflections delay in ms (0..300)
    val reverbLevel: Short = 0,        // Late reverberation level in mB (-9000..2000)
    val reverbDelayMs: Int = 40,       // Late reverberation delay in ms (0..100)
    val diffusion: Short = 1000,       // Echo density (0..1000)
    val density: Short = 1000,         // Modal density (0..1000)
)

/**
 * Audio DSP engine:
 * 1. 5-band hardware equalizer.
 * 2. Native EnvironmentalReverb (with fallback to PresetReverb) for physical room acoustics.
 * 3. LoudnessEnhancer for perceptual loudness normalization.
 * 4. Transparent equal-power volume crossfade calculation for smooth track transitions.
 */
class AudioEffects {
    private var sessionId = 0
    private var equalizer: Equalizer? = null
    private var environmentalReverb: EnvironmentalReverb? = null
    private var presetReverb: PresetReverb? = null
    private var loudness: LoudnessEnhancer? = null
    private var last: AppSettings? = null
    var nativeDsp: uniffi.spotifree_core_ffi.AudioProcessorBridge? = null
        private set

    init {
        runCatchingCancellable {
            nativeDsp = com.spotifree.app.core.SpotifreeCoreManager.audioProcessor
                ?: uniffi.spotifree_core_ffi.AudioProcessorBridge(44100, 2)
        }
    }

    fun attach(audioSessionId: Int, settings: AppSettings) {
        if (audioSessionId == 0 || audioSessionId == sessionId) {
            apply(settings)
            return
        }
        release()
        sessionId = audioSessionId

        equalizer = runCatchingCancellable { Equalizer(0, audioSessionId) }
            .onFailure { Log.w(TAG, "Failed to initialize Equalizer", it) }.getOrNull()

        // Attempt to create native EnvironmentalReverb first for authentic acoustics
        environmentalReverb = runCatchingCancellable { EnvironmentalReverb(0, audioSessionId) }
            .onFailure { Log.w(TAG, "EnvironmentalReverb not supported, falling back to PresetReverb", it) }.getOrNull()

        if (environmentalReverb == null) {
            presetReverb = runCatchingCancellable { PresetReverb(0, audioSessionId) }
                .onFailure { Log.w(TAG, "Failed to initialize PresetReverb fallback", it) }.getOrNull()
        }

        loudness = runCatchingCancellable { LoudnessEnhancer(audioSessionId) }
            .onFailure { Log.w(TAG, "Failed to initialize LoudnessEnhancer", it) }.getOrNull()

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
                val targetGainMb = if (settings.volumeNormalization) {
                    val gainDb = runCatching {
                        uniffi.spotifree_core_ffi.calculateGainAdjustment(-14f, -17f)
                    }.getOrDefault(3f)
                    (gainDb * 100).roundToInt().coerceIn(0, 1000)
                } else 0
                it.setTargetGain(targetGainMb)
                it.enabled = settings.volumeNormalization
            }
        }
    }

    private fun applyEqualizer(bandsDb: List<Int>) {
        runCatchingCancellable {
            bandsDb.forEachIndexed { idx, gain ->
                nativeDsp?.setEqualizerBand(idx, gain.toFloat())
            }
        }
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
        }.onFailure { Log.w(TAG, "Failed to apply equalizer bands", it) }
    }

    private fun applyReverb(preset: String) {
        val profile = getReverbProfile(preset)
        runCatchingCancellable {
            if (profile != null) {
                nativeDsp?.setReverbEnabled(true)
                nativeDsp?.setReverbRoomSize((profile.decayTimeMs / 10000f).coerceIn(0.1f, 0.95f))
            } else {
                nativeDsp?.setReverbEnabled(false)
            }
        }
        val env = environmentalReverb
        val pr = presetReverb

        if (env != null) {
            runCatchingCancellable {
                if (profile != null) {
                    env.roomLevel = profile.roomLevel
                    env.roomHFLevel = profile.roomHFLevel
                    env.decayTime = profile.decayTimeMs
                    env.decayHFRatio = profile.decayHFRatio
                    env.reflectionsLevel = profile.reflectionsLevel
                    env.reflectionsDelay = profile.reflectionsDelayMs
                    env.reverbLevel = profile.reverbLevel
                    env.reverbDelay = profile.reverbDelayMs
                    env.diffusion = profile.diffusion
                    env.density = profile.density
                    env.enabled = true
                } else {
                    env.enabled = false
                }
            }.onFailure { Log.w(TAG, "Failed to apply EnvironmentalReverb", it) }
        } else if (pr != null) {
            runCatchingCancellable {
                when (preset) {
                    "slowed_reverb" -> { pr.preset = PresetReverb.PRESET_LARGEHALL; pr.enabled = true }
                    "vaporwave" -> { pr.preset = PresetReverb.PRESET_PLATE; pr.enabled = true }
                    "nightcore" -> { pr.preset = PresetReverb.PRESET_SMALLROOM; pr.enabled = true }
                    else -> pr.enabled = false
                }
            }.onFailure { Log.w(TAG, "Failed to apply PresetReverb fallback", it) }
        }
    }

    fun release() {
        runCatchingCancellable { equalizer?.release() }
        runCatchingCancellable { environmentalReverb?.release() }
        runCatchingCancellable { presetReverb?.release() }
        runCatchingCancellable { loudness?.release() }
        equalizer = null
        environmentalReverb = null
        presetReverb = null
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

        /**
         * Resolves acoustic physical space parameters for style presets.
         */
        fun getReverbProfile(preset: String): ReverbProfile? = when (preset) {
            "slowed_reverb" -> ReverbProfile(
                roomLevel = -1000,
                decayTimeMs = 2800,
                reflectionsLevel = -1200,
            )
            "vaporwave" -> ReverbProfile(
                roomLevel = -1200,
                decayTimeMs = 1800,
                reflectionsLevel = -1000,
            )
            "nightcore" -> ReverbProfile(
                roomLevel = -1500,
                decayTimeMs = 800,
                reflectionsLevel = -2000,
            )
            else -> null
        }

        /**
         * Speed and pitch of each style preset.
         */
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

        /**
         * Calculates smooth volume interpolation factor for track crossfade.
         * Equal-power curve ensures constant perceived volume during cross-fading.
         */
        fun calculateCrossfadeVolume(
            positionMs: Long,
            durationMs: Long,
            crossfadeSec: Int,
            isNextTrack: Boolean = false,
            useEqualPower: Boolean = true,
        ): Float {
            val fadeMs = crossfadeSec * 1000L
            if (fadeMs <= 0) return 1.0f

            val rawFactor: Float = when {
                positionMs < fadeMs && isNextTrack -> {
                    (positionMs.toFloat() / fadeMs).coerceIn(0f, 1f)
                }
                durationMs > fadeMs && (durationMs - positionMs) < fadeMs -> {
                    ((durationMs - positionMs).toFloat() / fadeMs).coerceIn(0f, 1f)
                }
                else -> 1.0f
            }

            return if (useEqualPower) {
                sin(rawFactor * (Math.PI.toFloat() / 2f)).coerceIn(0f, 1f)
            } else {
                rawFactor
            }
        }
    }
}
