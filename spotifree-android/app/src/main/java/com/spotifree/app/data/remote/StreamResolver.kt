package com.spotifree.app.data.remote

import com.spotifree.app.util.runCatchingCancellable

import android.net.Uri
import android.util.Log
import com.spotifree.app.data.db.CacheDao
import com.spotifree.app.data.db.StreamFormatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap

data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val itag: Int,
    val userAgent: String,
    val expiresAt: Long,
    val client: String,
    val loudnessDb: Float? = null,
    val contentLength: Long = -1,
)

class StreamUnavailableException(val reason: String) : Exception(reason)

/**
 * Turns a YouTube video id into a direct audio URL.
 *  1. NewPipe Extractor (primary).
 *  2. Direct InnerTube "player" call with the clients that still return plain URLs (VISIONOS,
 *     then IOS / ANDROID_VR) - the same chain the previous app used, kept as an automatic
 *     fallback for when YouTube breaks the extractor before a new version is released.
 * Results are cached until shortly before the URL expires.
 */
class StreamResolver(
    private val http: OkHttpClient,
    private val cacheDao: CacheDao,
) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val json = Json { ignoreUnknownKeys = true }

    fun cached(videoId: String): ResolvedStream? =
        cache[videoId]?.takeIf { it.expiresAt - System.currentTimeMillis() > 10 * 60_000 }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /**
     * @param avoidNewPipe skip the extractor (its URL just failed with 403 / an error)
     */
    suspend fun resolve(videoId: String, quality: String, avoidNewPipe: Boolean = false): ResolvedStream =
        withContext(Dispatchers.IO) {
            if (!avoidNewPipe) cached(videoId)?.let { return@withContext it }
            var lastError: Throwable? = null
            if (!avoidNewPipe) {
                try {
                    return@withContext viaNewPipe(videoId, quality).also { remember(it) }
                } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
                    throw ec
                } catch (e: Throwable) {
                    lastError = e
                    Log.w(TAG, "NewPipe could not resolve $videoId: ${e.message}")
                    if (isDefinitive(e)) throw StreamUnavailableException("protected")
                }
            }
            for (client in CLIENTS) {
                try {
                    return@withContext viaInnerTube(videoId, quality, client).also { remember(it) }
                } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
                    throw ec
                } catch (e: Throwable) {
                    lastError = e
                    if (e is StreamUnavailableException && e.reason == "protected") throw e
                }
            }
            throw (lastError as? StreamUnavailableException) ?: StreamUnavailableException(lastError?.message ?: "no_formats")
        }

    private suspend fun remember(s: ResolvedStream) {
        cache[s.videoId] = s
        runCatchingCancellable { cacheDao.putFormat(StreamFormatEntity(s.videoId, s.itag, s.mimeType)) }
    }

    private fun isDefinitive(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return listOf("private", "removed", "copyright", "not available in your country", "age", "members")
            .any { m.contains(it) } && !m.contains("bot")
    }

    // ------------------------------------------------------------------ NewPipe

    private fun viaNewPipe(videoId: String, quality: String): ResolvedStream {
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeIds.watchUrl(videoId))
        val streams = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        if (streams.isEmpty()) throw StreamUnavailableException("no_formats")
        val chosen = pick(streams, quality)
        val url = chosen.content
        return ResolvedStream(
            videoId = videoId,
            url = url,
            mimeType = chosen.format?.mimeType ?: if (chosen.itag in 249..251) "audio/webm" else "audio/mp4",
            itag = chosen.itag,
            userAgent = userAgentFor(url),
            expiresAt = expiryOf(url),
            client = "newpipe",
        )
    }

    private fun pick(streams: List<AudioStream>, quality: String): AudioStream {
        val order = QUALITY_PRIORITY[quality] ?: QUALITY_PRIORITY.getValue("normal")
        for (itag in order) streams.firstOrNull { it.itag == itag }?.let { return it }
        return when (quality) {
            "high" -> streams.maxBy { it.averageBitrate }
            "saving" -> streams.minBy { if (it.averageBitrate > 0) it.averageBitrate else Int.MAX_VALUE }
            else -> streams.sortedBy { it.averageBitrate }.let { it[it.size / 2] }
        }
    }

    private fun userAgentFor(url: String): String {
        val client = Uri.parse(url).getQueryParameter("c")?.uppercase() ?: ""
        return when {
            client.startsWith("IOS") -> runCatchingCancellable { YoutubeParsingHelper.getIosUserAgent(null) }.getOrDefault(IOS_UA)
            client.startsWith("ANDROID_VR") -> ANDROID_VR_UA
            client.startsWith("ANDROID") -> runCatchingCancellable { YoutubeParsingHelper.getAndroidUserAgent(null) }.getOrDefault(ANDROID_UA)
            client.startsWith("VISIONOS") -> VISIONOS_UA
            else -> NewPipeDownloader.USER_AGENT
        }
    }

    private fun expiryOf(url: String): Long {
        val expire = Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()
        return if (expire != null) expire * 1000 else System.currentTimeMillis() + 5 * 3600_000L
    }

    // ------------------------------------------------------------------ InnerTube fallback

    private data class Client(
        val id: String,
        val endpoint: String,
        val userAgent: String,
        val clientNameNumber: String,
        val clientVersion: String,
        val context: JsonObject,
        val extraHeaders: Map<String, String> = emptyMap(),
    )

    private fun viaInnerTube(videoId: String, quality: String, client: Client): ResolvedStream {
        val body = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("context") { put("client", client.context) }
        }.toString()
        val req = Request.Builder()
            .url(client.endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientNameNumber)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .apply { client.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw StreamUnavailableException("http_${r.code}")
            r.body?.string() ?: throw StreamUnavailableException("empty")
        }
        val root = json.parseToJsonElement(text).jsonObject
        val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        if (status != null && status != "OK") {
            val reason = root["playabilityStatus"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull ?: status
            throw StreamUnavailableException(if (reason.contains("bot", true)) "bot_check" else if (status == "UNPLAYABLE" || status == "ERROR") "protected" else status)
        }
        val adaptive = root["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray
            ?: throw StreamUnavailableException("no_formats")
        val loudness = root["playerConfig"]?.jsonObject?.get("audioConfig")?.jsonObject
            ?.get("loudnessDb")?.jsonPrimitive?.floatOrNull
        data class F(val itag: Int, val url: String, val mime: String, val bitrate: Int, val length: Long)
        val formats = adaptive.mapNotNull { el ->
            val o = el.jsonObject
            val mime = o["mimeType"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!mime.startsWith("audio")) return@mapNotNull null
            F(
                itag = o["itag"]?.jsonPrimitive?.intOrNull ?: 0,
                url = url,
                mime = mime.substringBefore(';'),
                bitrate = o["bitrate"]?.jsonPrimitive?.intOrNull ?: 0,
                length = o["contentLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
            )
        }
        if (formats.isEmpty()) throw StreamUnavailableException("ciphered_or_missing_urls")
        val order = QUALITY_PRIORITY[quality] ?: QUALITY_PRIORITY.getValue("normal")
        val chosen = order.firstNotNullOfOrNull { itag -> formats.firstOrNull { it.itag == itag } } ?: formats.maxBy { it.bitrate }
        return ResolvedStream(
            videoId = videoId,
            url = chosen.url,
            mimeType = chosen.mime,
            itag = chosen.itag,
            userAgent = client.userAgent,
            expiresAt = expiryOf(chosen.url),
            client = client.id,
            loudnessDb = loudness,
            contentLength = chosen.length,
        )
    }

    companion object {
        private const val TAG = "StreamResolver"

        /** Same priorities as the previous app (Opus 249/250/251, AAC 139/140). */
        val QUALITY_PRIORITY = mapOf(
            "saving" to listOf(249, 139, 250, 140, 251),
            "normal" to listOf(250, 251, 140, 249, 139),
            "high" to listOf(251, 140, 250, 249, 139),
            // Chromecast's default receiver plays AAC everywhere
            "cast" to listOf(140, 251, 250, 249, 139),
        )

        const val IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        const val ANDROID_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
        const val ANDROID_VR_UA = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        const val VISIONOS_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        private val CLIENTS = listOf(
            Client(
                id = "VISIONOS",
                endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                userAgent = VISIONOS_UA,
                clientNameNumber = "101",
                clientVersion = "1.02",
                context = buildJsonObject {
                    put("clientName", "VISIONOS"); put("clientVersion", "1.02")
                    put("deviceMake", "Apple"); put("deviceModel", "RealityDevice17,1")
                    put("osName", "visionOS"); put("osVersion", "26.5.23O471")
                    put("hl", "en"); put("gl", "US")
                },
                extraHeaders = mapOf("Origin" to "https://www.youtube.com"),
            ),
            Client(
                id = "IOS",
                endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
                userAgent = IOS_UA,
                clientNameNumber = "5",
                clientVersion = "20.03.02",
                context = buildJsonObject {
                    put("clientName", "IOS"); put("clientVersion", "20.03.02")
                    put("osVersion", "18.3.2.22D82"); put("hl", "en"); put("gl", "US")
                },
                extraHeaders = mapOf("Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/"),
            ),
            Client(
                id = "ANDROID_VR",
                endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                userAgent = ANDROID_VR_UA,
                clientNameNumber = "28",
                clientVersion = "1.65.10",
                context = buildJsonObject {
                    put("clientName", "ANDROID_VR"); put("clientVersion", "1.65.10")
                    put("deviceMake", "Oculus"); put("deviceModel", "Quest 3")
                    put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
                    put("hl", "en"); put("gl", "US")
                },
                extraHeaders = mapOf("Origin" to "https://www.youtube.com"),
            ),
        )
    }
}
