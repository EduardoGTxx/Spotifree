package com.spotifree.app.data.download

import com.spotifree.app.util.runCatchingCancellable

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.spotifree.app.data.db.AppDatabase
import com.spotifree.app.data.db.DownloadEntity
import com.spotifree.app.data.db.DownloadState
import com.spotifree.app.data.library.LibraryRepository
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.prefs.SettingsRepository
import com.spotifree.app.data.remote.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Offline downloads. Each song is a WorkManager job (survives the app being closed, respects
 * "Wi-Fi only"), stored as a file in the app's storage and played from there.
 * Songs saved automatically while listening (isManual = false) are evicted first (LRU) when
 * they exceed the cache limit; manual downloads are never evicted.
 */
class DownloadRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    val streams: StreamResolver,
    private val settings: SettingsRepository,
    val http: OkHttpClient,
    scope: CoroutineScope,
) {
    private val dao = db.downloads()
    private val work = WorkManager.getInstance(context)

    val all: StateFlow<Map<String, DownloadEntity>> = dao.observeAll().map { list -> list.associateBy { it.trackId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val doneIds: StateFlow<Set<String>> = dao.observeDoneIds().map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun observeDownloadedTracks(): Flow<List<Track>> = dao.observeDoneTracks().map { l -> l.map { it.toTrack() } }

    fun observeTotalBytes() = dao.observeTotalBytes()

    val downloadsDir: File get() = File(context.filesDir, "downloads").apply { mkdirs() }
    val coversDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }

    fun fileBase(trackId: String) = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    suspend fun enqueue(track: Track, manual: Boolean = true) {
        if (track.isLocal) return
        library.save(track)
        val existing = dao.get(track.id)
        if (existing?.state == DownloadState.DONE) {
            if (manual && !existing.isManual) dao.upsert(existing.copy(isManual = true))
            return
        }
        if (existing?.state == DownloadState.RUNNING || existing?.state == DownloadState.QUEUED) return
        dao.upsert(DownloadEntity(trackId = track.id, state = DownloadState.QUEUED, isManual = manual))
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.current.downloadOnlyWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(DownloadWorker.KEY_TRACK_ID, track.id).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(workName(track.id), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun enqueueAll(tracks: List<Track>) {
        tracks.forEach { enqueue(it, manual = true) }
    }

    suspend fun cancel(trackId: String) {
        work.cancelUniqueWork(workName(trackId))
        remove(trackId)
    }

    suspend fun remove(trackId: String) = withContext(Dispatchers.IO) {
        dao.get(trackId)?.let { d ->
            d.filePath?.let { File(it).delete() }
            d.coverPath?.let { File(it).delete() }
        }
        dao.delete(trackId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        work.cancelAllWorkByTag(TAG)
        dao.done().forEach { remove(it.trackId) }
        downloadsDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun onLiked(track: Track) {
        if (settings.current.autoDownloadLiked) enqueue(track, manual = true)
    }

    /** "Save while listening": a song played almost entirely is kept for offline use. */
    suspend fun onPlayed(track: Track, seconds: Int, durationSec: Int) {
        if (!settings.current.autoCacheOnPlay || track.isLocal) return
        if (durationSec > 0 && seconds < durationSec * 0.8) return
        if (dao.get(track.id) != null) return
        enqueue(track, manual = false)
    }

    /** Evicts the oldest automatically saved songs above the cache limit. */
    suspend fun enforceCacheLimit() {
        val limit = settings.current.cacheSizeMb.toLong() * 1024 * 1024
        if (limit <= 0) return
        var total = dao.autoCachedBytes()
        if (total <= limit) return
        for (d in dao.autoCachedOldestFirst()) {
            if (total <= limit) break
            remove(d.trackId)
            total -= d.sizeBytes
        }
    }

    /** Copies a downloaded song to Music/Spotifree so other players can use it. */
    suspend fun exportToMusic(track: Track): Boolean = withContext(Dispatchers.IO) {
        val d = dao.get(track.id) ?: return@withContext false
        val src = d.filePath?.let { File(it) }?.takeIf { it.exists() } ?: return@withContext false
        val ext = src.extension.ifBlank { "m4a" }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${track.artist} - ${track.title}.$ext".replace(Regex("""[\\/:*?"<>|]"""), "_"))
            put(MediaStore.Audio.Media.MIME_TYPE, d.mimeType ?: "audio/mp4")
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.ARTIST, track.artist)
            put(MediaStore.Audio.Media.ALBUM, track.album.ifBlank { "Spotifree" })
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Spotifree")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        runCatchingCancellable {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }
    }

    internal suspend fun track(id: String) = library.track(id)
    internal suspend fun resolveOnYouTube(t: Track) = library.resolveOnYouTube(t)
    internal val quality get() = settings.current.audioQuality
    internal val dao_ get() = dao

    companion object {
        const val TAG = "spotifree-download"
        fun workName(trackId: String) = "dl-$trackId"
    }
}
