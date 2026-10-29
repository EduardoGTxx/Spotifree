package com.spotifree.app.data.download

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.spotifree.app.AppGraph
import com.spotifree.app.data.db.DownloadEntity
import com.spotifree.app.data.db.DownloadState
import com.spotifree.app.data.remote.StreamUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/**
 * Downloads one song: resolves its YouTube audio, fetches it in byte ranges (YouTube throttles
 * single long requests) with retries, then stores the cover next to it.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = AppGraph.get(applicationContext)
        val repo = graph.downloads
        val dao = graph.db.downloads()
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return@withContext Result.failure()
        val row = dao.get(trackId) ?: return@withContext Result.failure() // cancelled meanwhile
        var track = repo.track(trackId) ?: return@withContext Result.failure()
        dao.setState(trackId, DownloadState.RUNNING, 0f)
        try {
            if (track.youtubeId == null) track = repo.resolveOnYouTube(track) ?: throw StreamUnavailableException("not_found")
            val videoId = track.youtubeId ?: throw StreamUnavailableException("not_found")
            // A retry after a 403 / bot check skips the extractor and uses the fallback clients
            val stream = repo.streams.resolve(videoId, repo.quality, avoidNewPipe = runAttemptCount > 0)
            val ext = when {
                stream.mimeType.contains("webm") -> "webm"
                stream.mimeType.contains("mp4") || stream.mimeType.contains("m4a") -> "m4a"
                else -> "audio"
            }
            val base = repo.fileBase(trackId)
            val tmp = File(repo.downloadsDir, "$base.$ext.part")
            val target = File(repo.downloadsDir, "$base.$ext")
            val total = downloadRanged(repo, stream.url, stream.userAgent, tmp, stream.contentLength) { done, length ->
                val p = if (length > 0) done.toFloat() / length else 0f
                setProgress(workDataOf(KEY_PROGRESS to p))
                dao.setState(trackId, DownloadState.RUNNING, p)
            }
            if (total < 32 * 1024) throw IllegalStateException("file_too_small")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("rename_failed")
            // The song is playable as soon as the audio is saved; the cover never delays it
            val done = DownloadEntity(
                trackId = trackId,
                state = DownloadState.DONE,
                progress = 1f,
                filePath = target.absolutePath,
                mimeType = stream.mimeType,
                sizeBytes = target.length(),
                isManual = row.isManual,
            )
            dao.upsert(done)
            track.artworkUrl?.let { url -> downloadCover(repo, url, File(repo.coversDir, "$base.jpg")) }?.let { cover ->
                dao.upsert(done.copy(coverPath = cover.absolutePath, sizeBytes = done.sizeBytes + cover.length()))
            }
            if (!row.isManual) repo.enforceCacheLimit()
            Result.success()
        } catch (e: StreamUnavailableException) {
            dao.setState(trackId, DownloadState.FAILED, 0f, e.reason)
            if (e.reason == "protected" || e.reason == "not_found") Result.failure() else retryOrFail(dao, trackId, e)
        } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
            throw ec
        } catch (e: Exception) {
            retryOrFail(dao, trackId, e)
        }
    }

    private suspend fun retryOrFail(dao: com.spotifree.app.data.db.DownloadDao, trackId: String, e: Exception): Result {
        AppGraph.get(applicationContext).streams.let { s -> AppGraph.get(applicationContext).library.track(trackId)?.youtubeId?.let { s.invalidate(it) } }
        return if (runAttemptCount < 2) {
            dao.setState(trackId, DownloadState.QUEUED, 0f, e.message)
            Result.retry()
        } else {
            dao.setState(trackId, DownloadState.FAILED, 0f, e.message)
            Result.failure()
        }
    }

    private suspend fun downloadRanged(
        repo: DownloadRepository,
        url: String,
        userAgent: String,
        out: File,
        knownLength: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        var length = knownLength
        var done = if (out.exists()) out.length() else 0L
        RandomAccessFile(out, "rw").use { raf ->
            raf.seek(done)
            var lastReport = 0L
            while (length < 0 || done < length) {
                val end = done + CHUNK - 1
                val req = Request.Builder().url(url)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=$done-$end")
                    .build()
                var attempt = 0
                while (true) {
                    try {
                        repo.http.newCall(req).execute().use { r ->
                            if (r.code == 416) return done // past the end
                            if (!r.isSuccessful) throw IllegalStateException("http_${r.code}")
                            if (length < 0) {
                                length = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                    ?: r.body?.contentLength()?.takeIf { it > 0 && r.code == 200 } ?: -1
                            }
                            val body = r.body ?: throw IllegalStateException("empty")
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                var got = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    raf.write(buf, 0, read)
                                    done += read
                                    got += read
                                }
                                if (got == 0L) return done
                                if (r.code == 200) length = done // server ignored the range: whole file
                            }
                        }
                        break
                    } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
                        throw ec
                    } catch (e: Exception) {
                        if (++attempt >= 3) throw e
                        kotlinx.coroutines.delay(1000L * attempt)
                    }
                }
                if (done - lastReport > 256 * 1024 || done == length) {
                    lastReport = done
                    onProgress(done, length)
                }
                if (isStopped) throw IllegalStateException("stopped")
            }
        }
        return done
    }

    private fun downloadCover(repo: DownloadRepository, url: String, file: File): File? = runCatchingCancellable {
        val quick = repo.http.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        quick.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        file.takeIf { it.length() > 0 }
    }.getOrNull()

    companion object {
        const val KEY_TRACK_ID = "trackId"
        const val KEY_PROGRESS = "progress"
        private const val CHUNK = 4L * 1024 * 1024
    }
}
