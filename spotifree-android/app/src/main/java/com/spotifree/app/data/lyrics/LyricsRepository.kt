package com.spotifree.app.data.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

import com.spotifree.app.util.runCatchingCancellable

import android.util.Base64
import com.spotifree.app.data.db.CacheDao
import com.spotifree.app.data.db.LyricsCacheEntity
import com.spotifree.app.data.model.LyricLine
import com.spotifree.app.data.model.LyricWord
import com.spotifree.app.data.model.Lyrics
import com.spotifree.app.data.remote.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class LyricsResult(val lyrics: Lyrics?, val notFound: Boolean, val networkError: Boolean = false)

/**
 * Synced lyrics with the previous app's provider chain:
 *  1. SimpMusic (by YouTube video id)  2. LRCLIB exact  3. LRCLIB search (scored)
 *  4. KuGou (duration-matched)  5. plain lyrics as a last resort.
 * Results (and "not found" for 3 days) are cached in the database.
 */
class LyricsRepository(private val http: OkHttpClient, private val cache: CacheDao) {
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, LyricsResult>()

    @Serializable
    private data class CachedLine(val t: Double, val x: String, val w: List<CachedWord>? = null, val i: Boolean = false)

    @Serializable
    private data class CachedWord(val t: Double, val x: String)

    @Serializable
    private data class Cached(val synced: Boolean = false, val lines: List<CachedLine> = emptyList(), val source: String? = null, val notFound: Boolean = false)

    suspend fun fetch(
        rawTitle: String,
        rawArtist: String,
        durationSec: Int,
        videoId: String?,
        album: String?,
        force: Boolean = false,
    ): LyricsResult = withContext(Dispatchers.IO) {
        val title = cleanTitle(rawTitle)
        val artist = cleanArtist(rawArtist)
        if (title.isBlank()) return@withContext LyricsResult(null, notFound = true)
        val key = videoId?.let { "v:$it" } ?: "t:${MusicRepository.normalize(artist)}|${MusicRepository.normalize(title)}".take(180)
        if (!force) {
            memory[key]?.let { return@withContext it }
            cache.lyrics(key)?.let { row ->
                val c = runCatchingCancellable { json.decodeFromString(Cached.serializer(), row.json) }.getOrNull()
                if (c != null) {
                    if (c.notFound) {
                        if (System.currentTimeMillis() - row.savedAt < NEGATIVE_TTL) return@withContext LyricsResult(null, true).also { memory[key] = it }
                    } else if (c.lines.isNotEmpty()) {
                        val lyrics = Lyrics(c.synced, c.lines.map { l -> LyricLine(l.t, l.x, l.w?.map { LyricWord(it.t, it.x) }, l.i) }, c.source)
                        return@withContext LyricsResult(lyrics, false).also { memory[key] = it }
                    }
                }
            }
        }
        var best: Lyrics? = null
        var networkError = false
        val providers: List<suspend () -> Lyrics?> = listOf(
            { fromSimpMusic(videoId, durationSec) },
            { fromLrcLib(title, artist, album, durationSec) },
            { fromKuGou(title, artist, durationSec) },
        )
        for (p in providers) {
            // Changing songs cancels this fetch: stop before asking the next provider
            currentCoroutineContext().ensureActive()
            try {
                val res = p() ?: continue
                if (res.isSynced) {
                    best = res
                    break
                }
                if (best == null) best = res
            } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
                throw ec
            } catch (e: Exception) {
                // A provider refusing the request (403 from its firewall, 4xx) is not a connection
                // problem: the next provider is tried and the song may still end up "not found".
                // Timeouts, DNS errors and 5xx are transient and keep the result out of the cache.
                val refused = e is HttpStatusException && e.code in 400..499
                if (!refused) networkError = true
                android.util.Log.w("LyricsRepository", "provider failed: ${e.message}")
            }
        }
        if (best != null && best.lines.isNotEmpty()) {
            val result = LyricsResult(best, false)
            memory[key] = result
            store(key, Cached(best.isSynced, best.lines.map { l -> CachedLine(l.timeSec, l.text, l.words?.map { CachedWord(it.timeSec, it.text) }, l.isInstrumental) }, best.source))
            return@withContext result
        }
        val negative = LyricsResult(null, notFound = true, networkError = networkError)
        if (!networkError) {
            memory[key] = negative
            store(key, Cached(notFound = true))
        }
        negative
    }

    private suspend fun store(key: String, c: Cached) {
        runCatchingCancellable {
            cache.putLyrics(LyricsCacheEntity(key, json.encodeToString(Cached.serializer(), c)))
            cache.trimLyrics(400)
        }
    }

    suspend fun clearCache() {
        memory.clear()
        cache.clearLyrics()
    }

    // ------------------------------------------------------------------ providers

    private suspend fun get(url: String): JsonElement? {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        http.newCall(req).await().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            val body = r.body?.string() ?: return null
            return runCatchingCancellable { json.parseToJsonElement(body) }.getOrNull()
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }
    private fun JsonObject.num(k: String) = this[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.doubleOrNull }

    private suspend fun fromSimpMusic(videoId: String?, duration: Int): Lyrics? {
        if (videoId == null) return null
        val data = get("https://api-lyrics.simpmusic.org/v1/$videoId")?.jsonObject ?: return null
        val entries = (data["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        if (entries.isEmpty()) return null
        val sorted = entries.sortedWith(compareByDescending<JsonObject> { it["vote"]?.jsonPrimitive?.intOrNull ?: 0 }
            .thenBy { if (duration > 0) abs((it.num("durationSeconds") ?: 0.0) - duration) else 0.0 })
        for (e in sorted) {
            val synced = LrcParser.parse(e.str("syncedLyrics"))
            if (LrcParser.hasTimedLines(synced)) return Lyrics(true, synced, "SimpMusic")
        }
        val plain = LrcParser.parsePlain(sorted.first().str("plainLyric"))
        return if (plain.isNotEmpty()) Lyrics(false, plain, "SimpMusic") else null
    }

    private suspend fun fromLrcLib(title: String, artist: String, album: String?, duration: Int): Lyrics? {
        var plainFallback: Lyrics? = null
        val main = primaryArtist(artist)
        if (artist.isNotBlank()) {
            val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", main)
                .apply {
                    if (!album.isNullOrBlank() && !Regex("youtube|spotifree|radio|import", RegexOption.IGNORE_CASE).containsMatchIn(album)) addQueryParameter("album_name", album)
                    if (duration > 0) addQueryParameter("duration", duration.toString())
                }.build().toString()
            runCatchingCancellable { get(url)?.jsonObject }.getOrNull()?.let { exact ->
                if (exact["instrumental"]?.jsonPrimitive?.booleanOrNull != true) {
                    val synced = LrcParser.parse(exact.str("syncedLyrics"))
                    if (LrcParser.hasTimedLines(synced) && durationOk(exact.num("duration"), duration)) return Lyrics(true, synced, "LRCLIB")
                    exact.str("plainLyrics")?.let { plainFallback = Lyrics(false, LrcParser.parsePlain(it), "LRCLIB") }
                }
            }
        }
        val queries = buildList {
            if (artist.isNotBlank()) add("https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("track_name", title).addQueryParameter("artist_name", main).build().toString())
            add("https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("q", "$main $title".trim()).build().toString())
        }
        for (q in queries) {
            val list = runCatchingCancellable { get(q) as? JsonArray }.getOrNull()?.mapNotNull { it as? JsonObject } ?: continue
            if (list.isEmpty()) continue
            val ranked = list.map { it to score(it, title, artist, duration) }.sortedByDescending { it.second }
            for ((c, s) in ranked) {
                if (s < 4) break
                if (!durationOk(c.num("duration"), duration)) continue
                val synced = LrcParser.parse(c.str("syncedLyrics"))
                if (LrcParser.hasTimedLines(synced)) return Lyrics(true, synced, "LRCLIB")
            }
            if (plainFallback == null) {
                ranked.firstOrNull { it.second >= 6 && it.first.str("plainLyrics") != null }?.let {
                    plainFallback = Lyrics(false, LrcParser.parsePlain(it.first.str("plainLyrics")), "LRCLIB")
                }
            }
        }
        return plainFallback
    }

    private fun score(c: JsonObject, title: String, artist: String, duration: Int): Int {
        var s = 0
        val ct = MusicRepository.normalize(c.str("trackName") ?: c.str("name") ?: "")
        val ca = MusicRepository.normalize(c.str("artistName") ?: "")
        val nt = MusicRepository.normalize(title)
        val na = MusicRepository.normalize(primaryArtist(artist))
        if (ct.isNotEmpty() && nt.isNotEmpty()) s += if (ct == nt) 6 else if (ct.contains(nt) || nt.contains(ct)) 3 else 0
        if (ca.isNotEmpty() && na.isNotEmpty()) s += if (ca == na) 4 else if (ca.contains(na) || na.contains(ca)) 2 else 0
        val cd = c.num("duration")
        if (duration > 0 && cd != null) {
            val diff = abs(cd - duration)
            s += when {
                diff <= 2 -> 5
                diff <= 5 -> 3
                diff > 15 -> -6
                else -> 0
            }
        }
        if (c.str("syncedLyrics") != null) s += 4
        if (c["instrumental"]?.jsonPrimitive?.booleanOrNull == true) s -= 10
        return s
    }

    private suspend fun fromKuGou(title: String, artist: String, duration: Int): Lyrics? {
        val keyword = kugouKeyword(primaryArtist(artist), title)
        suspend fun download(candidate: JsonObject?): Lyrics? {
            val id = candidate?.str("id") ?: return null
            val accessKey = candidate.str("accesskey") ?: candidate.str("accessKey") ?: return null
            val url = "https://krcs.kugou.com/download".toHttpUrl().newBuilder()
                .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "pc")
                .addQueryParameter("fmt", "lrc").addQueryParameter("id", id).addQueryParameter("accesskey", accessKey)
                .build().toString()
            val content = (get(url) as? JsonObject)?.str("content") ?: return null
            val text = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
            val lines = LrcParser.parse(normalizeKugou(text))
            return if (LrcParser.hasTimedLines(lines)) Lyrics(true, lines, "KuGou") else null
        }
        val search = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108").addQueryParameter("plat", "0").addQueryParameter("pagesize", "8")
            .addQueryParameter("showtype", "0").addQueryParameter("keyword", keyword).build().toString()
        val infos = ((get(search) as? JsonObject)?.get("data") as? JsonObject)?.get("info")?.let { it as? JsonArray }
            ?.mapNotNull { it as? JsonObject }.orEmpty()
        if (infos.isNotEmpty() && duration > 0) {
            val tried = HashSet<String>()
            for (tolerance in 0..5) {
                for (info in infos) {
                    val hash = info.str("hash") ?: continue
                    if (abs((info.num("duration") ?: 0.0) - duration) <= tolerance && tried.add(hash)) {
                        val byHash = "https://krcs.kugou.com/search".toHttpUrl().newBuilder()
                            .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "mobi")
                            .addQueryParameter("hash", hash).build().toString()
                        val cand = ((get(byHash) as? JsonObject)?.get("candidates") as? JsonArray)?.firstOrNull() as? JsonObject
                        download(cand)?.let { return it }
                    }
                }
            }
        }
        val byKeyword = "https://krcs.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "mobi")
            .addQueryParameter("keyword", keyword).build().toString()
        val cand = ((get(byKeyword) as? JsonObject)?.get("candidates") as? JsonArray)?.firstOrNull() as? JsonObject
        return download(cand)
    }

    // ------------------------------------------------------------------ translation

    /**
     * Translates lines to the UI language (or to the other of es/en when the song is already in
     * it). @return translated texts aligned with [lines], and the target language.
     */
    suspend fun translate(lines: List<LyricLine>, uiLanguage: String): Pair<List<String>, String> = withContext(Dispatchers.IO) {
        val primary = if (uiLanguage == "en") "en" else uiLanguage.ifBlank { "es" }
        val secondary = if (primary == "es") "en" else "es"
        val idx = lines.indices.filter { !lines[it].isInstrumental && lines[it].text != LrcParser.INSTRUMENTAL }
        val out = Array(lines.size) { i -> if (lines[i].isInstrumental) "" else lines[i].text }
        var target = primary
        val chunks = idx.chunked(25)
        var first = true
        for (chunk in chunks) {
            val texts = chunk.map { lines[it].text }
            var (parts, detected) = translateChunk(texts, target)
            if (first && detected?.substringBefore('-') == target) {
                target = secondary
                parts = translateChunk(texts, target).first
            }
            first = false
            chunk.forEachIndexed { k, lineIdx -> parts.getOrNull(k)?.takeIf { it.isNotBlank() }?.let { out[lineIdx] = it } }
        }
        out.toList() to target
    }

    private suspend fun translateChunk(texts: List<String>, target: String): Pair<List<String>, String?> {
        // 1. One line per verse: Google keeps line breaks, so the output aligns with the input
        val (joined, detected) = gtx(texts.joinToString("\n"), target)
        val byLine = joined.split('\n').map { it.trim() }.let { if (it.size == texts.size + 1 && it.last().isEmpty()) it.dropLast(1) else it }
        if (byLine.size == texts.size) return byLine to detected
        // 2. An explicit ASCII-safe marker between verses
        val (marked, _) = gtx(texts.joinToString(DELIMITER), target)
        var parts = marked.split(Regex("""\s*\|{3}\s*""")).map { it.trim() }
        if (parts.size == texts.size + 1 && parts.last().isEmpty()) parts = parts.dropLast(1)
        if (parts.size == texts.size) return parts to detected
        // 3. Line by line, at most 2 requests at a time with a pause (Google answers 429 to bursts)
        val gate = kotlinx.coroutines.sync.Semaphore(2)
        val single = coroutineScope {
            texts.map { t ->
                async {
                    gate.withPermit {
                        val r = runCatchingCancellable { gtx(t, target).first.trim() }.getOrDefault("")
                        kotlinx.coroutines.delay(150)
                        r
                    }
                }
            }.awaitAll()
        }
        return single to detected
    }

    private suspend fun gtx(text: String, target: String): Pair<String, String?> {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx").addQueryParameter("sl", "auto").addQueryParameter("tl", target)
            .addQueryParameter("dt", "t").addQueryParameter("q", text).build()
        http.newCall(Request.Builder().url(url).build()).await().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val arr = json.parseToJsonElement(r.body?.string() ?: "[]").jsonArray
            val translated = arr.getOrNull(0)?.let { it as? JsonArray }?.joinToString("") { part ->
                (part as? JsonArray)?.getOrNull(0)?.let { p -> if (p is JsonObject || p is JsonArray) "" else p.jsonPrimitive.contentOrNull } ?: ""
            } ?: ""
            val detected = arr.getOrNull(2)?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }
            return translated to detected
        }
    }

    companion object {
        private const val USER_AGENT = "Spotifree/2.0 (https://github.com/EduardoGTxx/Spotifree)"
        private const val NEGATIVE_TTL = 3L * 24 * 3600 * 1000
        private const val DELIMITER = " ||| "

        fun cleanTitle(raw: String): String = raw
            .replace(Regex("""\(.*?(?:official|video|audio|lyric|remaster|version|edit|hd|4k|visualizer|letra).*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[.*?(?:official|video|audio|lyric|remaster|version|edit|hd|4k|visualizer|letra).*?]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(?:official|video|audio|lyric).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\|\s*.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        fun cleanArtist(raw: String): String = raw.replace(Regex(""" - Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ").trim()

        fun primaryArtist(artist: String) = artist.split(Regex(""",|&|/| x | y |\bfeat\.?|\bft\.?""", RegexOption.IGNORE_CASE)).first().trim()

        private fun durationOk(candidate: Double?, duration: Int) = duration <= 0 || candidate == null || abs(candidate - duration) <= 5

        private fun kugouKeyword(artist: String, title: String): String {
            var newTitle = title
            var featuring = ""
            val featIdx = title.indexOf(" (feat. ")
            if (featIdx != -1) {
                val end = title.indexOf(')', featIdx)
                if (end != -1) {
                    featuring = title.substring(featIdx + 8, end)
                    newTitle = (title.substring(0, featIdx) + title.substring(end + 1)).trim()
                }
            }
            val newArtist = (if (featuring.isNotEmpty()) "$artist, $featuring" else artist)
                .replace(", ", "、").replace(" & ", "、").replace(".", "")
            return "$newArtist - $newTitle"
        }

        private fun normalizeKugou(text: String): String {
            val meta = listOf("[ti:", "[ar:", "[al:", "[by:", "[hash:", "[sign:", "[qq:", "[total:", "[offset:", "[id:")
            return text.replace("\r\n", "\n").split('\n').filter { line ->
                val l = line.trim()
                meta.none { l.startsWith(it) } &&
                    !Regex("""^\[\d{2}:\d{2}\.\d{2}](Written by|Lyrics by|Composed by|Producer|作曲|作词)\s*[:：]""").containsMatchIn(l)
            }.joinToString("\n").replace("&apos;", "'")
        }
    }
}

/**
 * Suspending, cancellable OkHttp call: when the coroutine is cancelled (the user skipped to
 * another song) the HTTP request is cancelled too instead of finishing in the background.
 */
internal suspend fun okhttp3.Call.await(): okhttp3.Response = suspendCancellableCoroutine { cont ->
    enqueue(object : okhttp3.Callback {
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Non-2xx answer of a lyrics provider. */
internal class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")
