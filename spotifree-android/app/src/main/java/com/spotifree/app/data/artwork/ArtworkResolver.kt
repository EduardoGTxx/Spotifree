package com.spotifree.app.data.artwork

import com.spotifree.app.data.db.HistoryDao
import com.spotifree.app.data.db.TrackDao
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.remote.MusicRepository
import com.spotifree.app.data.remote.YouTubeIds
import com.spotifree.app.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * 4-tier HD square album cover waterfall established in Spotifree:
 *  1. Apple Music / iTunes Search (1000x1000bb.jpg)
 *  2. MusicBrainz -> Cover Art Archive (1200px / front-500)
 *  3. Deezer API (cover_xl 1000x1000 / cover_big)
 *  4. YouTube Music WEB_REMIX Songs search (square thumbnail upgraded to 1200x1200)
 *
 * Upgrades both tracks without a cover (Spotify/CSV imports) and tracks that only have a
 * low-res 4:3 `ytimg.com` video frame (`hqdefault.jpg`), persisting resolved covers in Room
 * and publishing them reactively via [resolvedCovers].
 */
class ArtworkResolver(
    private val http: OkHttpClient,
    private val tracks: TrackDao,
    private val history: HistoryDao? = null,
    /** Last resort: match the song on YouTube (also stores its video id, so it later plays at once). */
    private val youtubeFallback: suspend (Track) -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedInMemory = ConcurrentHashMap<String, Long>()
    private val permits = Semaphore(4)

    private val _resolvedCovers = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedCovers: StateFlow<Map<String, String>> = _resolvedCovers

    /** True when [track] has no cover or only has a raw 4:3 `ytimg.com` video frame that should be upgraded to square HD album art. */
    fun needsCoverUpgrade(track: Track): Boolean {
        if (track.isLocal || track.title.isBlank()) return false
        if (_resolvedCovers.value.containsKey(track.id)) return false
        return YouTubeIds.isLowResVideoThumbnail(track.coverUrl)
    }

    /** Resolves and stores the HD square cover of [track]. Safe to call for every visible row or active player track. */
    suspend fun ensureCover(track: Track) {
        if (!needsCoverUpgrade(track)) return
        val memFailAt = failedInMemory[track.id]
        if (memFailAt != null && System.currentTimeMillis() - memFailAt < RETRY_AFTER_MS) return
        if (!inFlight.add(track.id)) return
        try {
            permits.withPermit {
                if (_resolvedCovers.value.containsKey(track.id)) return
                val row = tracks.get(track.id)
                if (row?.coverUrl != null && !YouTubeIds.isLowResVideoThumbnail(row.coverUrl)) {
                    val upgraded = YouTubeIds.upgradeArtwork(row.coverUrl) ?: row.coverUrl
                    _resolvedCovers.update { it + (track.id to upgraded) }
                    return
                }
                val failedAt = row?.enrichFailedAt
                if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) {
                    failedInMemory[track.id] = failedAt
                    return
                }
                val cover = find(track.artist, track.title)
                    ?: (if (track.artworkUrl == null) runCatchingCancellable { youtubeFallback(track) }.getOrNull() else null)
                if (cover != null) {
                    _resolvedCovers.update { it + (track.id to cover) }
                    if (row != null) tracks.setCover(track.id, cover)
                    history?.setCover(track.id, cover)
                } else {
                    val now = System.currentTimeMillis()
                    failedInMemory[track.id] = now
                    if (row != null && row.coverUrl == null) tracks.markEnrichFailed(track.id, now)
                }
            }
        } finally {
            inFlight.remove(track.id)
        }
    }

    /** Executes the 4-provider waterfall: iTunes -> Cover Art Archive -> Deezer -> YouTube Music Square. */
    suspend fun find(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        val (cleanArtist, cleanTitle) = cleanArtistAndTitle(artist, title)
        if (cleanTitle.isBlank()) return@withContext null
        runCatchingCancellable { fromItunes(cleanArtist, cleanTitle) }.getOrNull()
            ?: runCatchingCancellable { fromCoverArtArchive(cleanArtist, cleanTitle) }.getOrNull()
            ?: runCatchingCancellable { fromDeezer(cleanArtist, cleanTitle) }.getOrNull()
            ?: runCatchingCancellable { fromYouTubeMusic(cleanArtist, cleanTitle) }.getOrNull()
    }

    private fun fromItunes(artist: String, title: String): String? {
        val query = "$artist $title".trim()
        if (query.isBlank()) return null
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()
        val results = getJson(url.toString())?.get("results") as? JsonArray ?: return null
        val candidates = results.mapNotNull { it as? JsonObject }
        val matched = candidates
            .filter { matches(it.s("artistName"), it.s("trackName"), artist, title) }
            .maxByOrNull { scoreCandidate(it.s("artistName"), it.s("trackName"), artist, title) }
            ?: return null
        return matched.s("artworkUrl100")?.let { YouTubeIds.upgradeArtwork(it, 1000) }
    }

    private fun fromCoverArtArchive(artist: String, title: String): String? {
        val escapedTitle = escapeLucene(title)
        if (escapedTitle.isBlank()) return null
        val query = buildString {
            append("recording:\"").append(escapedTitle).append("\"")
            val primaryArtist = artist.split(ARTIST_SPLIT_REGEX).firstOrNull()?.trim().orEmpty()
            if (primaryArtist.isNotBlank()) {
                append(" AND artist:\"").append(escapeLucene(primaryArtist)).append("\"")
            }
        }
        val mbUrl = "https://musicbrainz.org/ws/2/recording/".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "3")
            .build()
        val mbRoot = getJson(mbUrl.toString(), userAgent = MUSICBRAINZ_UA) ?: return null
        val recordings = mbRoot["recordings"] as? JsonArray ?: return null
        val releaseIds = ArrayList<String>(3)
        for (recEl in recordings) {
            val rec = recEl as? JsonObject ?: continue
            val recTitle = rec.s("title") ?: continue
            val credit = (rec["artist-credit"] as? JsonArray)?.mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                co.s("name") ?: (co["artist"] as? JsonObject)?.s("name")
            }?.joinToString(" ").orEmpty()
            if (matches(credit, recTitle, artist, title)) {
                val releases = rec["releases"] as? JsonArray ?: continue
                for (relEl in releases) {
                    val relId = (relEl as? JsonObject)?.s("id")?.takeIf { it.isNotBlank() } ?: continue
                    if (relId !in releaseIds) releaseIds += relId
                    if (releaseIds.size >= 3) break
                }
            }
            if (releaseIds.size >= 3) break
        }
        for (relId in releaseIds) {
            val caaJson = runCatchingCancellable {
                getJson("https://coverartarchive.org/release/$relId", userAgent = MUSICBRAINZ_UA)
            }.getOrNull() ?: continue
            val images = caaJson["images"] as? JsonArray ?: continue
            val front = images.mapNotNull { it as? JsonObject }.firstOrNull { it.s("front") == "true" }
                ?: images.firstOrNull() as? JsonObject
                ?: continue
            val thumbs = front["thumbnails"] as? JsonObject
            val best = thumbs?.s("1200")
                ?: thumbs?.s("large")
                ?: front.s("image")
                ?: "https://coverartarchive.org/release/$relId/front-500"
            return best.replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        }
        return null
    }

    private fun fromDeezer(artist: String, title: String): String? {
        val query = "$artist $title".trim()
        if (query.isBlank()) return null
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .build()
        val data = getJson(url.toString())?.get("data") as? JsonArray ?: return null
        val candidates = data.mapNotNull { it as? JsonObject }
        val best = candidates
            .filter { o ->
                val candArtist = (o["artist"] as? JsonObject)?.s("name")
                val candTitle = o.s("title_short") ?: o.s("title")
                matches(candArtist, candTitle, artist, title)
            }
            .maxByOrNull { o ->
                val candArtist = (o["artist"] as? JsonObject)?.s("name")
                val candTitle = o.s("title_short") ?: o.s("title")
                scoreCandidate(candArtist, candTitle, artist, title)
            } ?: return null
        val album = best["album"] as? JsonObject ?: return null
        val cover = album.s("cover_xl") ?: album.s("cover_big") ?: album.s("cover_medium") ?: return null
        return cover.replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
    }

    private fun fromYouTubeMusic(artist: String, title: String): String? {
        val query = "$artist $title".trim()
        if (query.isBlank()) return null
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250310.01.00")
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            // YouTube Music "Songs" filter
            put("params", "EgWKAQIIAWoKEAoQCRADEAQQBQ==")
        }.toString()

        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", WEB_REMIX_UA)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val root = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            json.parseToJsonElement(r.body?.string() ?: return null) as? JsonObject
        } ?: return null

        val items = findAllObjects(root, "musicResponsiveListItemRenderer")
        for (item in items) {
            val flex = item["flexColumns"] as? JsonArray ?: continue
            val candTitle = flexColumnText(flex, 0)
            val candByline = flexColumnText(flex, 1)
            val candArtist = candByline.split("•", "·").firstOrNull()?.trim().orEmpty()
            if (candTitle.isNotBlank() && !matches(candArtist, candTitle, artist, title)) continue

            val thumbs = findAllObjects(item["thumbnail"], "thumbnails")
                .flatMap { (it["thumbnails"] as? JsonArray).orEmpty().mapNotNull { el -> el as? JsonObject } }
            val squareThumbs = thumbs.filter { t ->
                val w = t["width"]?.jsonPrimitive?.intOrNull ?: 0
                val h = t["height"]?.jsonPrimitive?.intOrNull ?: 0
                w > 0 && h > 0 && (w.toFloat() / h.toFloat()) in 0.92f..1.08f
            }.sortedByDescending { it["width"]?.jsonPrimitive?.intOrNull ?: 0 }
            val bestUrl = squareThumbs.firstOrNull()?.s("url") ?: continue
            return YouTubeIds.upgradeArtwork(bestUrl, 1200)
        }
        return null
    }

    private fun flexColumnText(flexColumns: JsonArray, index: Int): String {
        val col = flexColumns.getOrNull(index) as? JsonObject ?: return ""
        val renderer = col["musicResponsiveListItemFlexColumnRenderer"] as? JsonObject ?: return ""
        val textObj = renderer["text"] as? JsonObject ?: return ""
        val runs = textObj["runs"] as? JsonArray ?: return ""
        return runs.mapNotNull { (it as? JsonObject)?.s("text") }.joinToString("").trim()
    }

    private fun findAllObjects(el: JsonElement?, targetKey: String, out: MutableList<JsonObject> = ArrayList()): List<JsonObject> {
        if (el == null || out.size > 40) return out
        when (el) {
            is JsonObject -> {
                if (targetKey in el) {
                    val v = el[targetKey]
                    if (v is JsonObject) out += v
                    else if (v is JsonArray) out += el
                }
                el.values.forEach { findAllObjects(it, targetKey, out) }
            }
            is JsonArray -> el.forEach { findAllObjects(it, targetKey, out) }
            else -> Unit
        }
        return out
    }

    private fun getJson(url: String, userAgent: String? = null): JsonObject? {
        val req = Request.Builder().url(url).apply {
            if (userAgent != null) header("User-Agent", userAgent)
            header("Accept", "application/json")
        }.build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            json.parseToJsonElement(r.body?.string() ?: return null) as? JsonObject
        }
    }

    private fun JsonObject.s(k: String): String? =
        this[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }

    companion object {
        private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000
        private const val MUSICBRAINZ_UA = "Spotifree/2.1 (https://github.com/EduardoGTxx/Spotifree)"
        private const val WEB_REMIX_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        private val ARTIST_SPLIT_REGEX = Regex(""",|&|\bfeat\.?|\bft\.?|\bx\b|\bwith\b""", RegexOption.IGNORE_CASE)
        private val GENERIC_ARTISTS = setOf("youtube", "youtube music", "artista", "varios artistas", "various artists", "unknown", "desconocido")
        private val VERSION_TAG_REGEX = Regex(
            """\b(official|oficial|video|audio|lyric|lyrics|letra|visualizer|remaster|remastered|live|en vivo|hd|4k|hq|mv|m/v|topic)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val KARAOKE_TRIBUTE_NOISE = Regex(
            """\b(karaoke|tribute|originally performed by|made famous by|in the style of|8-bit|8 bit|lullaby|ringtone|instrumental version|backing track)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Cleans noisy video tags and properly splits `"Artist - Song Title"` when the title
         * contains `" - "` so the artist is never mistaken for the song title.
         */
        fun cleanArtistAndTitle(rawArtist: String, rawTitle: String): Pair<String, String> {
            var artist = YouTubeIds.cleanArtist(rawArtist)
            if (artist.lowercase() in GENERIC_ARTISTS) artist = ""

            var title = MusicRepository.cleanTrackTitle(rawTitle)
                .replace(Regex("""\|[^|]*$"""), " ")
                .replace(Regex("""\bm/?v\b""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            // Handle "Artist - Song Title" or "Song Title - Official Video"
            val dashMatch = Regex("""^(.{2,60}?)\s+[-–—]\s+(.{2,})$""").find(title)
            if (dashMatch != null) {
                val left = dashMatch.groupValues[1].trim()
                val right = dashMatch.groupValues[2].trim()
                val normLeft = MusicRepository.normalize(left)
                val normRight = MusicRepository.normalize(right)
                val normArtist = MusicRepository.normalize(artist.split(ARTIST_SPLIT_REGEX).firstOrNull().orEmpty())

                when {
                    // Case 1: Title is "Rihanna - Diamonds" and artist is "Rihanna" (or artist is blank/generic)
                    artist.isBlank() && !VERSION_TAG_REGEX.containsMatchIn(right) -> {
                        artist = YouTubeIds.cleanArtist(left)
                        title = right
                    }
                    normArtist.isNotBlank() && (normLeft == normArtist || normLeft.contains(normArtist) || normArtist.contains(normLeft)) && normRight.isNotBlank() -> {
                        title = right
                    }
                    // Case 2: Title is "Diamonds - Rihanna" (reversed)
                    normArtist.isNotBlank() && (normRight == normArtist || normRight.contains(normArtist)) && normLeft.isNotBlank() -> {
                        title = left
                    }
                    // Case 3: Title is "Song Title - Remastered 2020" / "Song Title - Official Video"
                    VERSION_TAG_REGEX.containsMatchIn(right) -> {
                        title = left
                    }
                }
            }

            title = title
                .replace(Regex("""\s*\b(?:feat\.?|ft\.?|featuring)\b.*$""", RegexOption.IGNORE_CASE), "")
                .trim()

            return artist to title
        }

        /**
         * Accurate match between a provider candidate `(candArtist, candTitle)` and the wanted `(artist, title)`:
         * - Rejects karaoke/tribute/cover knockoffs when the wanted song is not one
         * - Handles `"Artist - Title"` prefixes properly
         * - Requires exact normalized match for short titles (`<= 3` chars) or whole-word containment for longer titles
         */
        fun matches(candArtist: String?, candTitle: String?, artist: String, title: String): Boolean {
            if (candArtist.isNullOrBlank() || candTitle.isNullOrBlank()) return false

            val wantRaw = "$artist $title"
            val candRaw = "$candArtist $candTitle"
            if (KARAOKE_TRIBUTE_NOISE.containsMatchIn(candRaw) && !KARAOKE_TRIBUTE_NOISE.containsMatchIn(wantRaw)) {
                return false
            }

            val (cleanWantArtist, cleanWantTitle) = cleanArtistAndTitle(artist, title)
            val ct = MusicRepository.normalize(candTitle.substringBefore(" (").substringBefore(" ["))
            val ca = MusicRepository.normalize(candArtist)
            val t = MusicRepository.normalize(cleanWantTitle.substringBefore(" (").substringBefore(" ["))
            val a = MusicRepository.normalize(cleanWantArtist.split(ARTIST_SPLIT_REGEX).firstOrNull().orEmpty())

            if (t.isEmpty() || ct.isEmpty()) return false
            if (!textMatches(ct, t)) return false
            if (a.isEmpty()) return true
            if (ca.isEmpty()) return false
            return textMatches(ca, a) || a.split(" ").filter { it.length >= 3 }.let { words ->
                words.isNotEmpty() && words.all { w -> containsWord(ca, w) }
            }
        }

        private fun scoreCandidate(candArtist: String?, candTitle: String?, artist: String, title: String): Int {
            val (cleanWantArtist, cleanWantTitle) = cleanArtistAndTitle(artist, title)
            val ct = MusicRepository.normalize(candTitle.orEmpty())
            val ca = MusicRepository.normalize(candArtist.orEmpty())
            val t = MusicRepository.normalize(cleanWantTitle)
            val a = MusicRepository.normalize(cleanWantArtist.split(ARTIST_SPLIT_REGEX).firstOrNull().orEmpty())
            var score = 0
            if (ct == t) score += 10 else if (containsWord(ct, t)) score += 5
            if (ca == a) score += 8 else if (a.isNotEmpty() && containsWord(ca, a)) score += 4
            val wantRemix = title.contains("remix", ignoreCase = true)
            val candRemix = candTitle.orEmpty().contains("remix", ignoreCase = true)
            if (wantRemix == candRemix) score += 3 else score -= 3
            val wantLive = title.contains("live", ignoreCase = true) || title.contains("en vivo", ignoreCase = true)
            val candLive = candTitle.orEmpty().contains("live", ignoreCase = true) || candTitle.orEmpty().contains("en vivo", ignoreCase = true)
            if (wantLive == candLive) score += 2 else score -= 2
            return score
        }

        private fun textMatches(candNorm: String, wantNorm: String): Boolean {
            if (candNorm == wantNorm) return true
            // Short titles (e.g. "505", "Yo", "Up", "Fe") must match exactly so they never match inside longer unrelated titles
            if (candNorm.length <= 3 || wantNorm.length <= 3) return false
            return containsWord(candNorm, wantNorm) || containsWord(wantNorm, candNorm)
        }

        private fun containsWord(haystack: String, needle: String): Boolean {
            if (needle.isBlank()) return false
            return Regex("""(?:^|\s)${Regex.escape(needle)}(?:\s|$)""").containsMatchIn(haystack)
        }

        private fun escapeLucene(s: String): String =
            s.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)"""), """\\$1""")
    }
}
