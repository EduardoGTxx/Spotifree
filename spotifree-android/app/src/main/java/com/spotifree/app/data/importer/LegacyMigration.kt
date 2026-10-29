package com.spotifree.app.data.importer

import com.spotifree.app.util.runCatchingCancellable

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.spotifree.app.AppGraph
import com.spotifree.app.data.db.DownloadEntity
import com.spotifree.app.data.db.DownloadState
import com.spotifree.app.data.db.HistoryEntity
import com.spotifree.app.data.db.TrackEntity
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.remote.YouTubeAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

data class MigrationProgress(
    val step: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
    val summary: MigrationSummary? = null,
)

data class MigrationSummary(
    val songs: Int,
    val playlists: Int,
    val liked: Int,
    val history: Int,
    val downloads: Int,
    val account: Boolean,
)

/**
 * One-time move of everything the previous (Capacitor) Spotifree kept inside its WebView:
 * library and settings (localStorage), queue, linked YouTube account, listening history and
 * offline downloads (IndexedDB). Installing this version over the old one keeps that data in the
 * app's folder; a hidden WebView opened on the old origin (https://localhost) reads it and hands
 * it over through a JavaScript bridge. Downloads are streamed in chunks straight to files.
 */
class LegacyMigration(private val context: Context, private val graph: AppGraph) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(MigrationProgress())
    val progress: StateFlow<MigrationProgress> = _progress

    /** True when the old app's WebView storage exists and was not migrated yet. */
    fun isNeeded(): Boolean {
        if (graph.settings.current.legacyMigrationDone) return false
        val dir = File(context.applicationInfo.dataDir, "app_webview")
        return dir.exists() && (dir.walkTopDown().maxDepth(4).any { it.name == "Local Storage" || it.name == "IndexedDB" })
    }

    suspend fun markDone() = graph.settings.update { it.copy(legacyMigrationDone = true) }

    private var history = ArrayList<HistoryEntity>()
    private var storeJson: String? = null
    private var queueJson: String? = null
    private var accountRaw: String? = null
    private val downloads = ArrayList<DownloadEntity>()
    private val downloadTracks = ArrayList<Track>()
    private var currentFile: FileOutputStream? = null
    private var currentFilePath: File? = null
    private var finished = CompletableDeferred<String?>()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun run(): MigrationSummary? {
        // Fresh state (the screen can retry)
        finished = CompletableDeferred()
        history = ArrayList()
        storeJson = null
        queueJson = null
        accountRaw = null
        downloads.clear()
        downloadTracks.clear()
        _progress.value = MigrationProgress(step = "library")
        val webView = withContext(Dispatchers.Main) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.databaseEnabled = true
                addJavascriptInterface(Bridge(), "SpotifreeMigrator")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.host == "localhost") {
                            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream("<!doctype html><html><body></body></html>".toByteArray()))
                        }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(SCRIPT, null)
                    }
                }
                loadUrl("https://localhost/")
            }
        }
        // finish(null) means success: map it to "" inside the timeout so null only means "timed out"
        val error: String? = when (val r = withTimeoutOrNull(30 * 60_000L) { finished.await() ?: "" }) {
            null -> "timeout"
            "" -> null
            else -> r
        }
        withContext(Dispatchers.Main) {
            webView.removeJavascriptInterface("SpotifreeMigrator")
            webView.destroy()
        }
        if (error != null && storeJson == null) {
            _progress.value = _progress.value.copy(finished = true, error = error)
            return null
        }
        val result = withContext(Dispatchers.IO) { apply() }
        // Everything was copied: free the old WebView storage (it held a second copy of every
        // downloaded song as IndexedDB blobs).
        if (error == null) withContext(Dispatchers.Main) { runCatchingCancellable { android.webkit.WebStorage.getInstance().deleteAllData() } }
        return result
    }

    private suspend fun apply(): MigrationSummary {
        _progress.value = MigrationProgress(step = "saving")
        val backup = BackupManager(context, graph.db, graph.settings)
        var summary = RestoreSummary(0, 0, 0, 0)
        storeJson?.let { raw ->
            runCatchingCancellable { summary = backup.restore(json.parseToJsonElement(raw) as JsonObject, applySettings = true) }
                .onFailure { Log.w(TAG, "store restore", it) }
        }
        // Queue (restored by the player on its next start)
        queueJson?.let { raw ->
            runCatchingCancellable {
                val q = json.parseToJsonElement(raw) as JsonObject
                val tracks = (q["queue"] as? JsonArray)?.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    Track(
                        id = id,
                        title = (o["title"] as? JsonPrimitive)?.content ?: id,
                        artist = (o["artist"] as? JsonPrimitive)?.content ?: "",
                        album = (o["album"] as? JsonPrimitive)?.content ?: "",
                        durationSec = (o["duration"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0,
                        coverUrl = (o["coverUrl"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") },
                        videoId = (o["youtubeId"] as? JsonPrimitive)?.content?.takeIf { it.length == 11 },
                        searchQuery = (o["youtubeSearchQuery"] as? JsonPrimitive)?.content,
                    ).takeUnless { o["isLocal"] != null || o["isLocalDevice"] != null }
                }.orEmpty()
                if (tracks.isNotEmpty()) {
                    graph.db.tracks().insertIgnore(tracks.map { TrackEntity.from(it) })
                    graph.db.queue().replace(tracks.map { it.id })
                    val index = (q["queueIndex"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                    val pos = ((q["currentTime"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0) * 1000
                    context.getSharedPreferences(com.spotifree.app.playback.PlaybackService.QUEUE_PREFS, Context.MODE_PRIVATE).edit()
                        .putInt("index", index.coerceIn(0, tracks.size - 1)).putLong("position", pos.toLong()).apply()
                }
            }.onFailure { Log.w(TAG, "queue", it) }
        }
        // Linked YouTube account
        var account = false
        accountRaw?.let { raw ->
            runCatchingCancellable {
                val o = json.parseToJsonElement(decodeAccount(raw)) as JsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                graph.account.save(YouTubeAccount(name = s("name"), email = s("email"), handle = s("channelHandle").ifBlank { s("handle") }, avatarUrl = s("avatarUrl"), cookie = s("cookie")))
                account = true
            }.onFailure { Log.w(TAG, "account", it) }
        }
        // History
        if (history.isNotEmpty()) {
            history.sortBy { it.playedAt }
            history.chunked(2000).forEach { graph.db.history().insertAll(it) }
        }
        // Downloads
        if (downloadTracks.isNotEmpty()) graph.db.tracks().insertIgnore(downloadTracks.map { TrackEntity.from(it) })
        downloads.forEach { graph.db.downloads().upsert(it) }
        markDone()
        val result = MigrationSummary(summary.tracks, summary.playlists, summary.liked, history.size, downloads.size, account)
        _progress.value = MigrationProgress(step = "done", finished = true, summary = result)
        return result
    }

    /** The old app obfuscated the account JSON: UTF-16 code units XOR 0x5A, base64. */
    private fun decodeAccount(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("{")) return t
        val bytes = Base64.decode(t, Base64.DEFAULT)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val unit = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            sb.append((unit xor 0x5a).toChar())
            i += 2
        }
        return sb.toString()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun localStorage(key: String, value: String?) {
            when (key) {
                "spotifree_store_v3" -> storeJson = value
                "spotifree_queue_v1" -> queueJson = value
                "spotifree_yt_account_v1" -> accountRaw = value
            }
        }

        @JavascriptInterface
        fun history(batch: String) {
            runCatchingCancellable {
                (json.parseToJsonElement(batch) as JsonArray).forEach { e ->
                    val o = e as? JsonObject ?: return@forEach
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.content
                    history += HistoryEntity(
                        trackId = s("trackId") ?: return@forEach,
                        title = s("title") ?: "",
                        artist = s("artist") ?: "",
                        coverUrl = s("coverUrl")?.takeIf { it.startsWith("http") },
                        playedAt = s("playedAt")?.toDoubleOrNull()?.toLong() ?: return@forEach,
                        secondsPlayed = s("secondsPlayed")?.toDoubleOrNull()?.toInt() ?: 30,
                        source = if (s("source") == "spotify") "spotify" else "app",
                    )
                }
            }
            _progress.value = MigrationProgress(step = "history", done = history.size)
        }

        @JavascriptInterface
        fun progress(step: String, done: Int, total: Int) {
            _progress.value = MigrationProgress(step = step, done = done, total = total)
        }

        @JavascriptInterface
        fun beginFile(trackId: String, ext: String) {
            closeFile()
            val base = graph.downloads.fileBase(trackId)
            val file = File(graph.downloads.downloadsDir, "$base.$ext")
            currentFilePath = file
            currentFile = FileOutputStream(file)
        }

        @JavascriptInterface
        fun chunk(b64: String) {
            runCatchingCancellable { currentFile?.write(Base64.decode(b64, Base64.DEFAULT)) }
        }

        @JavascriptInterface
        fun endFile(trackId: String, metaJson: String, mime: String, manual: Boolean, savedAt: Double) {
            closeFile()
            val file = currentFilePath ?: return
            currentFilePath = null
            if (file.length() < 16 * 1024) {
                file.delete()
                return
            }
            runCatchingCancellable {
                val m = json.parseToJsonElement(metaJson) as? JsonObject
                fun s(k: String) = (m?.get(k) as? JsonPrimitive)?.content
                downloadTracks += Track(
                    id = trackId,
                    title = s("title") ?: trackId,
                    artist = s("artist") ?: "",
                    album = s("album") ?: "",
                    durationSec = s("duration")?.toDoubleOrNull()?.toInt() ?: 0,
                    coverUrl = s("coverUrl")?.takeIf { it.startsWith("http") },
                    videoId = s("youtubeId")?.takeIf { it.length == 11 } ?: Track(trackId, "", "").youtubeId,
                )
            }
            downloads += DownloadEntity(
                trackId = trackId,
                state = DownloadState.DONE,
                progress = 1f,
                filePath = file.absolutePath,
                mimeType = mime.ifBlank { "audio/webm" },
                sizeBytes = file.length(),
                savedAt = savedAt.toLong().takeIf { it > 0 } ?: System.currentTimeMillis(),
                isManual = manual,
            )
        }

        @JavascriptInterface
        fun finish(error: String?) {
            closeFile()
            finished.complete(error?.takeIf { it.isNotBlank() })
        }

        private fun closeFile() {
            runCatchingCancellable { currentFile?.close() }
            currentFile = null
        }
    }

    companion object {
        private const val TAG = "LegacyMigration"

        /** Runs inside the hidden WebView on the old origin. */
        private val SCRIPT = """
            (async function () {
              const M = window.SpotifreeMigrator;
              try {
                ['spotifree_store_v3', 'spotifree_queue_v1', 'spotifree_yt_account_v1'].forEach(function (k) {
                  try { M.localStorage(k, localStorage.getItem(k)); } catch (e) {}
                });
                const db = await new Promise(function (resolve) {
                  const req = indexedDB.open('spotifree_offline_audio_v2');
                  req.onsuccess = function () { resolve(req.result); };
                  req.onerror = function () { resolve(null); };
                  req.onupgradeneeded = function () { };
                });
                if (db) {
                  const stores = Array.from(db.objectStoreNames);
                  if (stores.indexOf('playback_history') >= 0) {
                    const all = await new Promise(function (resolve) {
                      const r = db.transaction('playback_history', 'readonly').objectStore('playback_history').getAll();
                      r.onsuccess = function () { resolve(r.result || []); };
                      r.onerror = function () { resolve([]); };
                    });
                    for (let i = 0; i < all.length; i += 2000) M.history(JSON.stringify(all.slice(i, i + 2000)));
                  }
                  if (stores.indexOf('offline_tracks') >= 0) {
                    const keys = await new Promise(function (resolve) {
                      const r = db.transaction('offline_tracks', 'readonly').objectStore('offline_tracks').getAllKeys();
                      r.onsuccess = function () { resolve(r.result || []); };
                      r.onerror = function () { resolve([]); };
                    });
                    for (let i = 0; i < keys.length; i++) {
                      M.progress('downloads', i, keys.length);
                      const rec = await new Promise(function (resolve) {
                        const r = db.transaction('offline_tracks', 'readonly').objectStore('offline_tracks').get(keys[i]);
                        r.onsuccess = function () { resolve(r.result); };
                        r.onerror = function () { resolve(null); };
                      });
                      if (!rec || !rec.audioBlob) continue;
                      const blob = rec.audioBlob;
                      const mime = rec.mimeType || blob.type || 'audio/webm';
                      const ext = mime.indexOf('mp4') >= 0 || mime.indexOf('m4a') >= 0 || mime.indexOf('aac') >= 0 ? 'm4a' : (mime.indexOf('mpeg') >= 0 ? 'mp3' : 'webm');
                      M.beginFile(String(rec.id), ext);
                      const CHUNK = 768 * 1024;
                      for (let off = 0; off < blob.size; off += CHUNK) {
                        const b64 = await new Promise(function (resolve) {
                          const fr = new FileReader();
                          fr.onload = function () { resolve(String(fr.result).split(',')[1] || ''); };
                          fr.onerror = function () { resolve(''); };
                          fr.readAsDataURL(blob.slice(off, off + CHUNK));
                        });
                        M.chunk(b64);
                      }
                      M.endFile(String(rec.id), JSON.stringify(rec.metadata || {}), mime, rec.isManualDownload !== false, Number(rec.savedAt || 0));
                    }
                    M.progress('downloads', keys.length, keys.length);
                  }
                  db.close();
                }
                M.finish('');
              } catch (e) {
                M.finish(String(e && e.message || e));
              }
            })();
        """.trimIndent()
    }
}
