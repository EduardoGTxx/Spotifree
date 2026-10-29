package com.spotifree.app.data.remote

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import com.spotifree.app.data.model.PlaylistRef
import com.spotifree.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

@Serializable
data class YouTubeAccount(
    val name: String = "",
    val email: String = "",
    val handle: String = "",
    val avatarUrl: String = "",
    /** Session cookies of music.youtube.com (empty when linked by @handle only). */
    val cookie: String = "",
    val channelUrl: String = "",
)

/**
 * Optional link to the user's YouTube Music account, to import their playlists.
 *  - Sign in with Google (WebView, the app keeps the music.youtube.com session cookies), or
 *  - link a public channel by @handle (only public playlists).
 */
class YouTubeAccountRepository(
    context: Context,
    private val http: OkHttpClient,
    private val music: MusicRepository,
) {
    private val prefs = context.getSharedPreferences("yt_account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _account = MutableStateFlow(load())
    val account: StateFlow<YouTubeAccount?> = _account

    private fun load(): YouTubeAccount? =
        prefs.getString("account", null)?.let { runCatchingCancellable { json.decodeFromString(YouTubeAccount.serializer(), it) }.getOrNull() }

    fun save(account: YouTubeAccount?) {
        if (account == null) prefs.edit().remove("account").apply()
        else prefs.edit().putString("account", json.encodeToString(YouTubeAccount.serializer(), account)).apply()
        _account.value = account
    }

    fun disconnect() = save(null)

    /** Called by the sign-in screen once Google redirected to music.youtube.com with a session. */
    suspend fun completeSignIn(cookie: String): YouTubeAccount = withContext(Dispatchers.IO) {
        val profile = runCatchingCancellable { fetchProfile(cookie) }.getOrNull()
        val acc = YouTubeAccount(
            name = profile?.name ?: "YouTube Music",
            email = profile?.email.orEmpty(),
            handle = profile?.handle.orEmpty(),
            avatarUrl = profile?.avatarUrl.orEmpty(),
            cookie = cookie,
        )
        save(acc)
        acc
    }

    suspend fun connectByHandle(handleOrUrl: String): Pair<YouTubeAccount, List<PlaylistRef>> = withContext(Dispatchers.IO) {
        val h = handleOrUrl.trim()
        val url = when {
            h.startsWith("http") -> h
            h.startsWith("@") -> "https://www.youtube.com/$h"
            h.startsWith("UC") && h.length == 24 -> "https://www.youtube.com/channel/$h"
            else -> "https://www.youtube.com/@$h"
        }
        val (name, lists) = music.channelPlaylists(url)
        val acc = YouTubeAccount(name = name, handle = if (h.startsWith("@")) h else "@$h", channelUrl = url)
        save(acc)
        acc to lists
    }

    /** The user's playlists (library) from YouTube Music. */
    suspend fun playlists(): List<PlaylistRef> = withContext(Dispatchers.IO) {
        val acc = _account.value ?: return@withContext emptyList()
        if (acc.cookie.isBlank()) {
            if (acc.channelUrl.isBlank()) return@withContext emptyList()
            return@withContext music.channelPlaylists(acc.channelUrl).second
        }
        val root = browse(acc.cookie, "FEmusic_liked_playlists")
        val out = ArrayList<PlaylistRef>()
        findAll(root, "musicTwoRowItemRenderer").forEach { r ->
            val browseId = r.path("navigationEndpoint", "browseEndpoint", "browseId")?.str() ?: return@forEach
            if (!browseId.startsWith("VL")) return@forEach
            val listId = browseId.removePrefix("VL")
            val title = r.path("title", "runs")?.firstRunText().orEmpty()
            val cover = (r.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JsonArray)
                    ?.lastOrNull()?.let { (it as? JsonObject)?.get("url")?.str() }
            out += PlaylistRef(title = title, owner = acc.name, url = YouTubeIds.playlistUrl(listId), coverUrl = YouTubeIds.upgradeArtwork(cover))
        }
        out.distinctBy { it.url }
    }

    /** Songs of a playlist: authenticated (private lists) when signed in, NewPipe otherwise. */
    suspend fun playlistTracks(listId: String): List<Track> = withContext(Dispatchers.IO) {
        val acc = _account.value
        if (acc != null && acc.cookie.isNotBlank()) {
            val tracks = runCatchingCancellable { privatePlaylist(acc.cookie, listId) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) return@withContext tracks
        }
        music.collection(YouTubeIds.playlistUrl(listId)).tracks
    }

    private fun privatePlaylist(cookie: String, listId: String): List<Track> {
        val tracks = ArrayList<Track>()
        var root: JsonElement = browse(cookie, "VL$listId")
        var guard = 0
        while (guard++ < 30) {
            findAll(root, "musicResponsiveListItemRenderer").forEach { r -> (r as? JsonObject)?.let { parseSong(it) }?.let { tracks += it } }
            val token = findAll(root, "nextContinuationData").firstOrNull()?.let { (it as? JsonObject)?.get("continuation")?.str() }
                ?: findAll(root, "continuationCommand").firstOrNull()?.let { (it as? JsonObject)?.get("token")?.str() }
                ?: break
            root = browse(cookie, null, continuation = token)
        }
        return tracks.distinctBy { it.id }
    }

    private fun parseSong(r: JsonObject): Track? {
        val videoId = r.path("playlistItemData", "videoId")?.str()
            ?: findAll(r, "watchEndpoint").firstOrNull()?.let { (it as? JsonObject)?.get("videoId")?.str() }
            ?: return null
        val columns = r["flexColumns"] as? JsonArray ?: return null
        fun col(i: Int) = (columns.getOrNull(i) as? JsonObject)?.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs") as? JsonArray
        val title = col(0)?.firstRunText() ?: return null
        val artist = col(1)?.mapNotNull { (it as? JsonObject)?.get("text")?.str() }?.joinToString("")?.split(" • ")?.firstOrNull().orEmpty()
        val durText = (r["fixedColumns"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
            ?.path("musicResponsiveListItemFixedColumnRenderer", "text", "runs")?.let { (it as? JsonArray)?.firstRunText() }
        val parts = durText?.split(':')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val duration = if (parts.size >= 2) parts.fold(0) { acc, n -> acc * 60 + n } else 0
        val thumb = (r.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JsonArray)
            ?.lastOrNull()?.let { (it as? JsonObject)?.get("url")?.str() }
        return Track(
            id = Track.idForVideo(videoId),
            title = title,
            artist = YouTubeIds.cleanArtist(artist).ifBlank { "YouTube" },
            durationSec = duration,
            coverUrl = YouTubeIds.upgradeArtwork(thumb),
            videoId = videoId,
        )
    }

    // ------------------------------------------------------------------ InnerTube (WEB_REMIX, signed in)

    private fun browse(cookie: String, browseId: String?, continuation: String? = null): JsonElement {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250310.01.00")
                    put("hl", "es")
                    put("gl", "US")
                }
            }
            if (browseId != null) put("browseId", browseId)
            if (continuation != null) put("continuation", continuation)
        }.toString()
        val url = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false" +
            (continuation?.let { "&ctoken=$it&continuation=$it&type=next" } ?: "")
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Cookie", cookie)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Goog-AuthUser", "0")
            .apply { authorization(cookie)?.let { header("Authorization", it) } }
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return json.parseToJsonElement(r.body?.string() ?: "{}")
        }
    }

    private data class Profile(val name: String, val email: String, val handle: String, val avatarUrl: String)

    private fun fetchProfile(cookie: String): Profile? {
        val req = Request.Builder().url("$ORIGIN/getAccountSwitcherEndpoint")
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Cookie", cookie)
            .apply { authorization(cookie)?.let { header("Authorization", it) } }
            .build()
        val text = http.newCall(req).execute().use { it.body?.string() } ?: return null
        val root = json.parseToJsonElement(text.removePrefix(")]}'").trim())
        val header = findAll(root, "accountItem").firstOrNull() as? JsonObject ?: return null
        val name = header.path("accountName", "simpleText")?.str().orEmpty()
        val handle = header.path("channelHandle", "simpleText")?.str().orEmpty()
        val email = header.path("accountByline", "simpleText")?.str().orEmpty()
        val avatar = (header.path("accountPhoto", "thumbnails") as? JsonArray)?.lastOrNull()?.let { (it as? JsonObject)?.get("url")?.str() }.orEmpty()
        return Profile(name, email, handle, avatar)
    }

    companion object {
        private const val ORIGIN = "https://music.youtube.com"

        /** "SAPISIDHASH <ts>_<sha1(ts SAPISID origin)>" required by signed-in InnerTube calls. */
        fun authorization(cookie: String): String? {
            val sapisid = Regex("""(?:^|;\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)""").find(cookie)?.groupValues?.get(1) ?: return null
            val ts = System.currentTimeMillis() / 1000
            val digest = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray())
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${ts}_$hex"
        }

        fun findAll(el: JsonElement?, key: String, out: MutableList<JsonElement> = ArrayList()): List<JsonElement> {
            when (el) {
                is JsonObject -> el.forEach { (k, v) ->
                    if (k == key) out += v
                    findAll(v, key, out)
                }
                is JsonArray -> el.forEach { findAll(it, key, out) }
                else -> Unit
            }
            return out
        }

        private fun JsonElement.path(vararg keys: String): JsonElement? {
            var cur: JsonElement? = this
            for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
            return cur
        }

        private fun JsonElement.str(): String? = if (this is JsonObject || this is JsonArray) null else jsonPrimitive.contentOrNull

        private fun JsonElement.firstRunText(): String? = ((this as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text")?.str()
    }
}
