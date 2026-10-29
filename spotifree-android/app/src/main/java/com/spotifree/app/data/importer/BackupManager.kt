package com.spotifree.app.data.importer

import com.spotifree.app.util.runCatchingCancellable

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.spotifree.app.BuildConfig
import com.spotifree.app.data.db.AppDatabase
import com.spotifree.app.data.db.BlockedArtistEntity
import com.spotifree.app.data.db.BlockedTrackEntity
import com.spotifree.app.data.db.FollowedArtistEntity
import com.spotifree.app.data.db.LikeEntity
import com.spotifree.app.data.db.PlaylistEntity
import com.spotifree.app.data.db.TrackEntity
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.prefs.AppSettings
import com.spotifree.app.data.prefs.SettingsRepository
import com.spotifree.app.data.remote.YouTubeAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

data class RestoreSummary(val tracks: Int, val playlists: Int, val liked: Int, val artists: Int)

/**
 * Backups in the same JSON format as the previous app ("Spotifree_Backup_*.json"), so old
 * backups restore here and new ones stay readable. Also reads the previous app's saved state
 * (same shape) during the automatic migration.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ------------------------------------------------------------------ export

    suspend fun export(account: YouTubeAccount?): String = withContext(Dispatchers.IO) {
        val liked = db.likes().likedIds()
        val playlists = db.playlists().all()
        val playlistTracks = playlists.associate { it.playlist.id to db.playlists().trackIds(it.playlist.id) }
        val ids = (liked + playlistTracks.values.flatten()).toSet()
        val tracks = ids.toList().chunked(900).flatMap { db.tracks().getMany(it) }.filter { it.localUri == null }
        val s = settings.current
        val followed = db.artists().followed()
        val blockedArtists = db.blocks().artists()
        val blockedTrackIds = db.blocks().trackIds()
        buildJsonObject {
            put("app", "Spotifree")
            put("version", BuildConfig.VERSION_NAME)
            put("exportedAt", Instant.now().toString())
            if (account != null) putJsonObject("account") {
                put("name", account.name); put("email", account.email); put("handle", account.handle)
            } else put("account", JsonNull)
            putJsonArray("playlists") {
                playlists.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.playlist.id)
                        put("title", p.playlist.title)
                        p.playlist.coverUrl?.let { put("coverUrl", it) }
                        p.playlist.youtubePlaylistId?.let { put("youtubePlaylistId", it) }
                        if (p.playlist.isSpotifyImport) put("isSpotifyImport", true)
                        putJsonArray("tracks") { playlistTracks[p.playlist.id].orEmpty().forEach { add(JsonPrimitive(it)) } }
                        put("trackCount", playlistTracks[p.playlist.id].orEmpty().size)
                    })
                }
            }
            putJsonArray("likedTrackIds") { liked.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("followedArtists") {
                followed.forEach { a ->
                    add(buildJsonObject {
                        put("name", a.name); a.imageUrl?.let { put("imageUrl", it) }; a.url?.let { put("browseId", it) }; put("followedAt", a.followedAt)
                    })
                }
            }
            putJsonArray("savedTracks") {
                tracks.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id); put("title", t.title); put("artist", t.artist)
                        if (t.album.isNotBlank()) put("album", t.album)
                        if (t.durationSec > 0) put("duration", t.durationSec)
                        t.coverUrl?.takeIf { it.startsWith("http") }?.let { put("coverUrl", it) }
                        t.videoId?.let { put("youtubeId", it) }
                        t.searchQuery?.let { put("youtubeSearchQuery", it) }
                    })
                }
            }
            putJsonObject("settings") {
                put("language", s.language)
                put("enableTGymIntegration", s.enableTGym)
                put("tgymLiveSync", s.tgymLiveSync)
                put("tgymDuckingLevel", s.tgymDuckingLevel)
                put("workoutRestDuration", s.workoutRestSec)
                put("audioDownloadQuality", s.audioQuality)
                put("downloadOnlyWifi", s.downloadOnlyWifi)
                put("autoCacheOnPlay", s.autoCacheOnPlay)
                put("crossfadeDuration", s.crossfadeSec)
                put("volumeNormalization", s.volumeNormalization)
                put("autoplayRadio", s.autoplayRadio)
                put("equalizerPreset", s.equalizerPreset)
                putJsonArray("equalizerBands") { s.equalizerBands.forEach { add(JsonPrimitive(it)) } }
                put("skipSilence", s.skipSilence)
                put("autoDownloadLiked", s.autoDownloadLiked)
                put("lruCacheSizeMB", s.cacheSizeMb)
                put("autoPauseOnNoisy", s.autoPauseOnNoisy)
                put("autoResumeOnBluetooth", s.autoResumeOnBluetooth)
                put("autoBackupFrequency", s.autoBackupFrequency)
                put("smartPrefetch", s.smartPrefetch)
                put("lyricsFontSize", s.lyricsFontSize)
                put("lyricsKaraoke", s.lyricsKaraoke)
                put("lyricsRomanization", s.lyricsRomanization)
                put("accentColor", s.accentColor)
                put("amoledTheme", s.amoledTheme)
                putJsonArray("blockedArtists") { blockedArtists.forEach { add(JsonPrimitive(it.key)) } }
                putJsonArray("blockedTrackIds") { blockedTrackIds.forEach { add(JsonPrimitive(it)) } }
            }
        }.toString()
    }

    // ------------------------------------------------------------------ restore

    fun parse(text: String): JsonObject {
        val root = json.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject
            ?: throw IllegalArgumentException("invalid_backup")
        if (root["playlists"] == null && root["likedTrackIds"] == null && root["savedTracks"] == null) throw IllegalArgumentException("invalid_backup")
        return root
    }

    /** Merges a backup (or the previous app's saved state) into the library. Nothing is deleted. */
    suspend fun restore(root: JsonObject, applySettings: Boolean = true): RestoreSummary = withContext(Dispatchers.IO) {
        val tracks = LinkedHashMap<String, Track>()
        (root["savedTracks"] as? JsonArray)?.forEach { e -> (e as? JsonObject)?.let { trackFrom(it) }?.let { tracks.putIfAbsent(it.id, it) } }
        val playlists = ArrayList<Pair<PlaylistEntity, List<String>>>()
        (root["playlists"] as? JsonArray)?.forEachIndexed { index, e ->
            val p = e as? JsonObject ?: return@forEachIndexed
            val id = p.s("id") ?: return@forEachIndexed
            if (id == Playlist.LIKED_ID || p.b("isSpecialLiked") == true) return@forEachIndexed
            val ids = ArrayList<String>()
            (p["tracks"] as? JsonArray)?.forEach { te ->
                when (te) {
                    is JsonObject -> trackFrom(te)?.let { tracks.putIfAbsent(it.id, it); ids += it.id }
                    is JsonPrimitive -> te.contentOrNull?.let { ids += it }
                    else -> Unit
                }
            }
            val ytList = p.s("youtubePlaylistId") ?: id.takeIf { it.startsWith("yt-pl-") }?.removePrefix("yt-pl-")
            playlists += PlaylistEntity(
                id = id,
                title = p.s("title") ?: "Playlist",
                coverUrl = p.s("coverUrl")?.takeIf { it.startsWith("http") },
                youtubePlaylistId = ytList,
                isSpotifyImport = p.b("isSpotifyImport") == true || id.startsWith("pl-spotify"),
                sortIndex = System.currentTimeMillis() - index,
            ) to ids.distinct()
        }
        val liked = (root["likedTrackIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().distinct()
        val known = tracks.keys
        db.withTransaction {
            tracks.values.chunked(500).forEach { chunk -> db.tracks().insertIgnore(chunk.map { TrackEntity.from(it) }) }
            // Songs referenced only by id that were never saved get a minimal row from their id
            val referenced = (liked + playlists.flatMap { it.second }).filter { it !in known }.distinct()
            if (referenced.isNotEmpty()) {
                val missing = referenced.chunked(900).flatMap { chunk ->
                    val have = db.tracks().getMany(chunk).map { it.id }.toSet()
                    chunk.filter { it !in have }
                }
                db.tracks().insertIgnore(missing.mapNotNull { id ->
                    Track(id = id, title = id, artist = "", videoId = Track(id, "", "").youtubeId).takeIf { it.youtubeId != null }?.let { TrackEntity.from(it) }
                })
            }
            val now = System.currentTimeMillis()
            val existingLikes = db.likes().likedIds().toHashSet()
            db.likes().insertAll(liked.filter { it !in existingLikes }.mapIndexed { i, id -> LikeEntity(id, now - i * 1000L) })
            for ((p, ids) in playlists) {
                val existing = db.playlists().get(p.id)
                if (existing == null) {
                    db.playlists().upsert(p)
                    db.playlists().replaceTracks(p.id, ids)
                } else {
                    val merged = (db.playlists().trackIds(p.id) + ids).distinct()
                    db.playlists().replaceTracks(p.id, merged)
                }
            }
            (root["followedArtists"] as? JsonArray)?.forEach { e ->
                val a = e as? JsonObject ?: return@forEach
                val name = a.s("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                db.artists().follow(FollowedArtistEntity(key = name.lowercase(), name = name, imageUrl = a.s("imageUrl"), url = a.s("browseId")?.takeIf { it.startsWith("http") }, followedAt = a.l("followedAt") ?: now))
            }
            val settingsObj = root["settings"] as? JsonObject ?: root
            (settingsObj["blockedArtists"] as? JsonArray)?.forEach { e ->
                (e as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { db.blocks().blockArtist(BlockedArtistEntity(it, it)) }
            }
            (settingsObj["blockedTrackIds"] as? JsonArray)?.forEach { e ->
                (e as? JsonPrimitive)?.contentOrNull?.let { db.blocks().blockTrack(BlockedTrackEntity(it)) }
            }
        }
        if (applySettings) {
            val so = root["settings"] as? JsonObject ?: root
            settings.update { applyLegacySettings(it, so) }
        }
        RestoreSummary(tracks.size, playlists.size, liked.size, (root["followedArtists"] as? JsonArray)?.size ?: 0)
    }

    private fun applyLegacySettings(s: AppSettings, o: JsonObject): AppSettings = s.copy(
        language = o.s("language")?.takeIf { it in listOf("es", "en", "pt", "fr") } ?: s.language,
        enableTGym = o.b("enableTGymIntegration") ?: s.enableTGym,
        tgymLiveSync = o.b("tgymLiveSync") ?: s.tgymLiveSync,
        tgymDuckingLevel = o.f("tgymDuckingLevel") ?: s.tgymDuckingLevel,
        workoutRestSec = o.i("workoutRestDuration") ?: s.workoutRestSec,
        audioQuality = o.s("audioDownloadQuality")?.takeIf { it in listOf("saving", "normal", "high") } ?: s.audioQuality,
        downloadOnlyWifi = o.b("downloadOnlyWifi") ?: s.downloadOnlyWifi,
        autoCacheOnPlay = o.b("autoCacheOnPlay") ?: s.autoCacheOnPlay,
        crossfadeSec = o.i("crossfadeDuration") ?: s.crossfadeSec,
        volumeNormalization = o.b("volumeNormalization") ?: s.volumeNormalization,
        autoplayRadio = o.b("autoplayRadio") ?: s.autoplayRadio,
        equalizerPreset = o.s("equalizerPreset") ?: s.equalizerPreset,
        equalizerBands = (o["equalizerBands"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }?.takeIf { it.size == 5 } ?: s.equalizerBands,
        skipSilence = o.b("skipSilence") ?: s.skipSilence,
        autoDownloadLiked = o.b("autoDownloadLiked") ?: s.autoDownloadLiked,
        cacheSizeMb = o.i("lruCacheSizeMB") ?: s.cacheSizeMb,
        autoPauseOnNoisy = o.b("autoPauseOnNoisy") ?: s.autoPauseOnNoisy,
        autoResumeOnBluetooth = o.b("autoResumeOnBluetooth") ?: s.autoResumeOnBluetooth,
        autoBackupFrequency = o.s("autoBackupFrequency") ?: s.autoBackupFrequency,
        smartPrefetch = o.b("smartPrefetch") ?: s.smartPrefetch,
        lyricsFontSize = o.s("lyricsFontSize") ?: s.lyricsFontSize,
        lyricsKaraoke = o.b("lyricsKaraoke") ?: s.lyricsKaraoke,
        lyricsRomanization = o.b("lyricsRomanization") ?: s.lyricsRomanization,
        accentColor = o.s("accentColor")?.takeIf { it.startsWith("#") } ?: s.accentColor,
        amoledTheme = o.b("amoledTheme") ?: s.amoledTheme,
        offlineMode = o.b("isOfflineMode") ?: s.offlineMode,
        recognitionProvider = o.s("recognitionProvider") ?: s.recognitionProvider,
        recognitionApiKey = o.s("recognitionApiKey") ?: s.recognitionApiKey,
        jamServerUrl = o.s("jamServerUrl") ?: s.jamServerUrl,
        castReceiverAppId = o.s("castReceiverAppId") ?: s.castReceiverAppId,
        driveAutoBackup = o.b("driveAutoBackup") ?: s.driveAutoBackup,
        workoutStreakDays = o.i("workoutStreakDays") ?: s.workoutStreakDays,
        lastWorkoutDate = o.s("lastWorkoutDate") ?: s.lastWorkoutDate,
    )

    private fun trackFrom(o: JsonObject): Track? {
        val id = o.s("id") ?: return null
        if (o.b("isLocal") == true || o.b("isLocalDevice") == true || id.startsWith("local")) return null
        val cover = o.s("coverUrl")?.takeIf { it.startsWith("http") && !it.contains("unsplash.com") }
        val yt = o.s("youtubeId")?.takeIf { it.length == 11 }
        return Track(
            id = id,
            title = o.s("title") ?: return null,
            artist = o.s("artist") ?: "",
            album = o.s("album")?.takeIf { !it.equals("Spotify Import", true) && !it.equals("YouTube Music", true) } ?: "",
            durationSec = o.i("duration") ?: 0,
            coverUrl = cover,
            videoId = yt,
            searchQuery = o.s("youtubeSearchQuery"),
        )
    }

    // ------------------------------------------------------------------ files

    /** Writes Download/Spotifree/<name> (visible to file managers; replaces the previous copy). */
    suspend fun writeToDownloads(name: String, content: String): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val relative = Environment.DIRECTORY_DOWNLOADS + "/Spotifree/"
        val existing = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, relative),
            null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
        val uri = existing ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
        }) ?: return@withContext null
        resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) } ?: return@withContext null
        uri
    }

    suspend fun writeTo(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
    }

    suspend fun readFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw IllegalStateException("unreadable")
    }

    /** Automatic backup per the user's frequency. @return true when a copy was written. */
    suspend fun runAutoBackupIfDue(account: YouTubeAccount?, reason: String = "startup"): Boolean {
        val s = settings.current
        val now = System.currentTimeMillis()
        val due = when (s.autoBackupFrequency) {
            "daily" -> now - s.lastAutoBackupAt > 20L * 3600 * 1000
            "weekly" -> now - s.lastAutoBackupAt > 6L * 24 * 3600 * 1000
            "on_exit" -> reason == "exit"
            else -> false
        }
        if (!due) return false
        val uri = runCatchingCancellable { writeToDownloads(AUTO_BACKUP_FILE, export(account)) }.getOrNull() ?: return false
        settings.update { it.copy(lastAutoBackupAt = now) }
        return uri != null
    }

    companion object {
        const val AUTO_BACKUP_FILE = "Spotifree_AutoBackup.json"

        private fun JsonObject.p(k: String): JsonPrimitive? = this[k] as? JsonPrimitive
        fun JsonObject.s(k: String): String? = p(k)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull?.takeIf { it != "null" }
        fun JsonObject.b(k: String): Boolean? = p(k)?.booleanOrNull
        fun JsonObject.i(k: String): Int? = p(k)?.intOrNull ?: p(k)?.doubleOrNull?.toInt()
        fun JsonObject.l(k: String): Long? = p(k)?.longOrNull
        fun JsonObject.f(k: String): Float? = p(k)?.floatOrNull
    }
}
