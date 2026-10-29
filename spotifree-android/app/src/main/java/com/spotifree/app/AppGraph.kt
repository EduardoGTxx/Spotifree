package com.spotifree.app

import android.content.Context
import com.spotifree.app.data.db.AppDatabase
import com.spotifree.app.data.download.DownloadRepository
import com.spotifree.app.data.library.LibraryRepository
import com.spotifree.app.data.lyrics.LyricsRepository
import com.spotifree.app.data.prefs.SettingsRepository
import com.spotifree.app.data.remote.MusicRepository
import com.spotifree.app.data.remote.NewPipeDownloader
import com.spotifree.app.data.remote.StreamResolver
import com.spotifree.app.data.remote.YouTubeAccountRepository
import com.spotifree.app.playback.PlayerConnection
import com.spotifree.app.playback.TrackResolver
import com.spotifree.app.ui.common.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Process-wide singletons (manual dependency injection: the graph is small and explicit).
 * Created in [SpotifreeApp.onCreate]; the playback service and the UI share these instances.
 */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val db: AppDatabase = AppDatabase.create(context)
    val settings = SettingsRepository(context, scope)
    val music = MusicRepository()
    val streams = StreamResolver(http, db.cache())
    val library = LibraryRepository(db, music, scope)
    val downloads = DownloadRepository(context, db, library, streams, settings, http, scope)
    val lyrics = LyricsRepository(http, db.cache())
    val account = YouTubeAccountRepository(context, http, music)
    val messages = Messages()
    val playbackEvents = com.spotifree.app.playback.PlaybackEvents()

    val jam = com.spotifree.app.data.jam.JamClient(http, scope)
    val recognizer = com.spotifree.app.data.recognition.Recognizer(context, http)
    val updates = com.spotifree.app.data.update.UpdateChecker(context, http)
    val localMusic = com.spotifree.app.data.local.LocalMusicScanner(context, db)
    val artwork = com.spotifree.app.data.artwork.ArtworkResolver(http, db.tracks()) { t -> library.resolveOnYouTube(t)?.artworkUrl }
    val backups = com.spotifree.app.data.importer.BackupManager(context, db, settings)

    val trackResolver = TrackResolver(context, db, library, streams, settings)
    val player = PlayerConnection(context, scope)

    init {
        com.spotifree.app.playback.MediaItems.localCover = { id ->
            downloads.all.value[id]?.coverPath?.takeIf { it.isNotBlank() }
        }
        val locale = Locale.getDefault()
        val country = locale.country.ifBlank { "US" }
        NewPipe.init(
            NewPipeDownloader(http),
            Localization(locale.language.ifBlank { "es" }, country),
            ContentCountry(country),
        )
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}
