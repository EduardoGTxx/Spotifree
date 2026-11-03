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
import com.spotifree.app.playback.OfflineModeException
import com.spotifree.app.playback.PlayerConnection
import com.spotifree.app.playback.TrackResolver
import com.spotifree.app.ui.common.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Dns
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

    val db: AppDatabase = AppDatabase.create(context)
    val settings = SettingsRepository(context, scope)

    val http: OkHttpClient = buildHttpClient(isOffline = { settings.current.offlineMode })

    val music = MusicRepository(http)
    val streams = StreamResolver(http, db.cache())
    val library = LibraryRepository(db, music, scope)
    val downloads = DownloadRepository(context, db, library, streams, settings, http, scope)
    val lyrics = LyricsRepository(http, db.cache(), isOffline = { settings.current.offlineMode })
    val account = YouTubeAccountRepository(context, http, music)
    val messages = Messages()
    val playbackEvents = com.spotifree.app.playback.PlaybackEvents()

    val jam = com.spotifree.app.data.jam.JamClient(http, scope) { settings.current.offlineMode }
    val recognizer = com.spotifree.app.data.recognition.Recognizer(context, http) { settings.current.offlineMode }
    val updates = com.spotifree.app.data.update.UpdateChecker(context, http)
    val localMusic = com.spotifree.app.data.local.LocalMusicScanner(context, db)
    val artwork = com.spotifree.app.data.artwork.ArtworkResolver(
        http = http,
        tracks = db.tracks(),
        history = db.history(),
        isOffline = { settings.current.offlineMode },
    ) { t -> library.resolveOnYouTube(t)?.artworkUrl }
    val backups = com.spotifree.app.data.importer.BackupManager(context, db, settings)

    val trackResolver = TrackResolver(context, db, library, streams, settings)
    val player = PlayerConnection(context, scope)

    init {
        com.spotifree.app.playback.MediaItems.localCover = { id ->
            downloads.all.value[id]?.coverPath?.takeIf { it.isNotBlank() }
        }
        com.spotifree.app.playback.MediaItems.resolvedCover = { id ->
            artwork.resolvedCovers.value[id]
        }
        val locale = Locale.getDefault()
        val country = locale.country.ifBlank { "US" }
        NewPipe.init(
            NewPipeDownloader(http),
            Localization(locale.language.ifBlank { "es" }, country),
            ContentCountry(country),
        )
        // Immediately sever all active sockets, in-flight requests, WebSockets, and download jobs when Offline Mode is enabled
        scope.launch {
            settings.settings
                .map { it.offlineMode }
                .distinctUntilChanged()
                .collect { offline ->
                    if (offline) {
                        runCatching { jam.leave() }
                        runCatching { http.dispatcher.cancelAll() }
                        runCatching { http.connectionPool.evictAll() }
                        runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(DownloadRepository.TAG) }
                    }
                }
        }
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }

        /**
         * Builds an [OkHttpClient] guarded by a 3-layer Offline Mode firewall (Application Interceptor,
         * Network Interceptor, and DNS blocker) so zero Wi-Fi or mobile data packets can leave the app
         * while [isOffline] returns true.
         */
        internal fun buildHttpClient(isOffline: () -> Boolean): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (isOffline()) throw OfflineModeException()
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor { chain ->
                if (isOffline()) throw OfflineModeException()
                chain.proceed(chain.request())
            }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (isOffline()) throw OfflineModeException()
                    return Dns.SYSTEM.lookup(hostname)
                }
            })
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
