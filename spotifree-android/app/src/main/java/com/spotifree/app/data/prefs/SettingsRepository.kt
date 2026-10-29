package com.spotifree.app.data.prefs

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every user preference of the app. Defaults mirror the previous app so a migrated user keeps
 * the same behaviour. Stored as one JSON document (unknown/old keys are ignored).
 */
@Serializable
data class AppSettings(
    val language: String = "es",
    // Appearance
    val accentColor: String = "#2979ff",
    val amoledTheme: Boolean = false,
    // Playback
    val crossfadeSec: Int = 3,
    val volumeNormalization: Boolean = true,
    val autoplayRadio: Boolean = true,
    val skipSilence: Boolean = false,
    val playbackSpeed: Float = 1f,
    val preservePitch: Boolean = true,
    val effectPreset: String = "normal", // normal | slowed_reverb | nightcore | vaporwave
    val equalizerPreset: String = "flat",
    val equalizerBands: List<Int> = listOf(0, 0, 0, 0, 0),
    val autoPauseOnNoisy: Boolean = true,
    val autoResumeOnBluetooth: Boolean = false,
    val smartPrefetch: Boolean = false,
    // Downloads & cache
    val audioQuality: String = "normal", // saving | normal | high
    val downloadOnlyWifi: Boolean = true,
    val autoCacheOnPlay: Boolean = false,
    val autoDownloadLiked: Boolean = false,
    val cacheSizeMb: Int = 1024,
    val offlineMode: Boolean = false,
    // Lyrics
    val lyricsFontSize: String = "md", // sm | md | lg | xl
    val lyricsKaraoke: Boolean = true,
    val lyricsRomanization: Boolean = false,
    // Backups
    val autoBackupFrequency: String = "off", // off | daily | weekly | on_exit
    val lastAutoBackupAt: Long = 0,
    val driveAutoBackup: Boolean = false,
    val lastDriveBackupAt: Long = 0,
    // Integrations
    val recognitionProvider: String = "audd", // audd | acrcloud
    val recognitionApiKey: String = "",
    val jamServerUrl: String = "",
    val castReceiverAppId: String = "",
    // TGym companion
    val enableTGym: Boolean = false,
    val tgymLiveSync: Boolean = true,
    val tgymDuckingLevel: Float = 0.3f,
    val workoutRestSec: Int = 90,
    val workoutStreakDays: Int = 0,
    val lastWorkoutDate: String? = null,
    // App update
    val dismissedUpdateVersion: String? = null,
    // Migration from the Capacitor app
    val legacyMigrationDone: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun decode(raw: String?): AppSettings =
        raw?.let { runCatchingCancellable { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()

    /** Read once synchronously at startup (theme and language must be known before the first frame). */
    private val initial: AppSettings = runBlocking { decode(context.dataStore.data.first()[key]) }

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map { decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    val current: AppSettings get() = settings.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(decode(prefs[key]))
            prefs[key] = json.encodeToString(AppSettings.serializer(), next)
        }
    }
}
