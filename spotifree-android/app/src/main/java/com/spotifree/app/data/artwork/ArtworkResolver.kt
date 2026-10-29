package com.spotifree.app.data.artwork

import com.spotifree.app.data.db.TrackDao
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.remote.MusicRepository
import com.spotifree.app.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a cover for songs that have none yet (Spotify/CSV imports are matched to YouTube only
 * when played). Apple Music (iTunes Search) first, then Deezer; both are free and keyless.
 * A found cover is saved in the database, so it survives restarts and works offline; misses are
 * remembered for a week so lists never hammer the services.
 */
class ArtworkResolver(
    private val http: OkHttpClient,
    private val tracks: TrackDao,
    /** Last resort: match the song on YouTube (also stores its video id, so it later plays at once). */
    private val youtubeFallback: suspend (Track) -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(3)

    /** Resolves and stores the cover of [track] when it has none. Safe to call for every visible row. */
    suspend fun ensureCover(track: Track) {
        if (track.artworkUrl != null || track.isLocal || track.title.isBlank()) return
        if (!inFlight.add(track.id)) return
        try {
            permits.withPermit {
                val row = tracks.get(track.id) ?: return
                if (row.coverUrl != null) return
                val failedAt = row.enrichFailedAt
                if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return
                val cover = find(track.artist, track.title) ?: runCatchingCancellable { youtubeFallback(track) }.getOrNull()
                if (cover != null) tracks.setCover(track.id, cover) else tracks.markEnrichFailed(track.id, System.currentTimeMillis())
            }
        } finally {
            inFlight.remove(track.id)
        }
    }

    suspend fun find(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        runCatchingCancellable { fromItunes(artist, title) }.getOrNull()
            ?: runCatchingCancellable { fromDeezer(artist, title) }.getOrNull()
    }

    private fun fromItunes(artist: String, title: String): String? {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $title")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "15")
            .build()
        val results = getJson(url.toString())?.get("results") as? JsonArray ?: return null
        val best = results.mapNotNull { it as? JsonObject }.firstOrNull { matches(it.s("artistName"), it.s("trackName"), artist, title) }
            ?: return null
        return best.s("artworkUrl100")?.replace("100x100bb", "600x600bb")
    }

    private fun fromDeezer(artist: String, title: String): String? {
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$artist $title")
            .addQueryParameter("limit", "10")
            .build()
        val data = getJson(url.toString())?.get("data") as? JsonArray ?: return null
        val best = data.mapNotNull { it as? JsonObject }.firstOrNull { o ->
            matches((o["artist"] as? JsonObject)?.s("name"), o.s("title"), artist, title)
        } ?: return null
        return (best["album"] as? JsonObject)?.s("cover_xl")
    }

    private fun getJson(url: String): JsonObject? =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            json.parseToJsonElement(r.body?.string() ?: return null) as? JsonObject
        }

    private fun JsonObject.s(k: String): String? = this[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }

    companion object {
        private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

        /** Loose match: the candidate's title and main artist must contain the wanted ones (accents ignored). */
        fun matches(candArtist: String?, candTitle: String?, artist: String, title: String): Boolean {
            if (candArtist == null || candTitle == null) return false
            val ct = MusicRepository.normalize(candTitle)
            val ca = MusicRepository.normalize(candArtist)
            val t = MusicRepository.normalize(title.substringBefore(" (").substringBefore(" - "))
            val a = MusicRepository.normalize(artist.split(Regex(""",|&| x | feat\.? """, RegexOption.IGNORE_CASE)).first())
            return t.isNotEmpty() && a.isNotEmpty() && (ct.contains(t) || t.contains(ct)) && (ca.contains(a) || a.contains(ca))
        }
    }
}
