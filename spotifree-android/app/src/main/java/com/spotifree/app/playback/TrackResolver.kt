package com.spotifree.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.spotifree.app.data.db.AppDatabase
import com.spotifree.app.data.library.LibraryRepository
import com.spotifree.app.data.prefs.SettingsRepository
import com.spotifree.app.data.remote.NewPipeDownloader
import com.spotifree.app.data.remote.StreamResolver
import com.spotifree.app.data.remote.StreamUnavailableException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.Collections

/** Thrown from the loader thread when a song cannot be played (the service skips it). */
class TrackUnavailableException(val trackId: String, val reason: String) : IOException("unplayable:$reason")

/**
 * Picks the real audio source of "spotifree://track/<id>" when the player opens it:
 *  1. a finished download (file in app storage)
 *  2. a song stored on the phone
 *  3. a YouTube stream (found first on YouTube Music for Spotify/CSV imports), read through the
 *     streaming cache so recently played songs replay without data and work offline.
 */
@UnstableApi
class TrackResolver(
    private val context: Context,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    private val streams: StreamResolver,
    private val settings: SettingsRepository,
) : ResolvingDataSource.Resolver {

    /** Video ids whose extractor URL failed: the next attempt goes straight to the fallback clients. */
    val avoidNewPipe: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        val trackId = MediaItems.trackIdOf(uri) ?: return dataSpec
        return runBlocking { resolve(dataSpec, trackId, uri) }
    }

    private suspend fun resolve(dataSpec: DataSpec, trackId: String, uri: Uri): DataSpec {
        // 1. Download
        db.downloads().get(trackId)?.let { d ->
            val path = d.filePath
            if (d.state == com.spotifree.app.data.db.DownloadState.DONE && path != null && File(path).exists()) {
                db.downloads().touch(trackId)
                return dataSpec.withUri(Uri.fromFile(File(path)))
            }
        }
        var track = library.track(trackId)
        // 2. Local file
        val local = track?.localUri
        if (local != null) return dataSpec.withUri(Uri.parse(local))

        // 3. YouTube
        var videoId = track?.youtubeId ?: uri.getQueryParameter("v")
        if (videoId == null && track != null) {
            if (settings.current.offlineMode) throw TrackUnavailableException(trackId, "offline")
            track = library.resolveOnYouTube(track)
            videoId = track?.youtubeId
        }
        if (videoId == null) throw TrackUnavailableException(trackId, "not_found")

        val offline = settings.current.offlineMode || !Network.isOnline(context)
        if (offline) {
            val format = db.cache().format(videoId) ?: throw TrackUnavailableException(trackId, "offline")
            // Served entirely from the streaming cache when the song was played before
            return dataSpec.buildUpon()
                .setUri(Uri.parse("https://offline.spotifree/$videoId"))
                .setKey(cacheKey(videoId, format.itag))
                .build()
        }
        val resolved = try {
            streams.resolve(videoId, settings.current.audioQuality, avoidNewPipe = videoId in avoidNewPipe)
        } catch (e: StreamUnavailableException) {
            throw TrackUnavailableException(trackId, e.reason)
        }
        return dataSpec.buildUpon()
            .setUri(Uri.parse(resolved.url))
            .setKey(cacheKey(videoId, resolved.itag))
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + mapOf("User-Agent" to resolved.userAgent))
            .build()
    }

    companion object {
        fun cacheKey(videoId: String, itag: Int) = "yt:$videoId:$itag"

        @Volatile
        private var cacheInstance: SimpleCache? = null

        /** One streaming cache per process (SimpleCache locks its folder). */
        fun cache(context: Context, maxMb: Int): SimpleCache = cacheInstance ?: synchronized(this) {
            cacheInstance ?: SimpleCache(
                File(context.cacheDir, "media"),
                LeastRecentlyUsedCacheEvictor(maxMb.coerceAtLeast(64).toLong() * 1024 * 1024),
                StandaloneDatabaseProvider(context),
            ).also { cacheInstance = it }
        }

        fun dataSourceFactory(
            context: Context,
            http: OkHttpClient,
            resolver: TrackResolver,
            cache: SimpleCache,
            cacheWrites: () -> Boolean,
        ): DataSource.Factory {
            val okHttp = OkHttpDataSource.Factory(http)
                .setDefaultRequestProperties(mapOf("User-Agent" to NewPipeDownloader.USER_AGENT))
            val upstream = DefaultDataSource.Factory(context, okHttp)
            val cached = DataSource.Factory {
                val cacheSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstream)
                    .setCacheWriteDataSinkFactory(if (cacheWrites()) CacheDataSink.Factory().setCache(cache) else null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
                // Downloads and phone files are read directly (never copied into the cache)
                SwitchingDataSource(cacheSource, upstream.createDataSource())
            }
            return ResolvingDataSource.Factory(cached, resolver)
        }
    }
}
