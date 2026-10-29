package com.spotifree.app.data.importer

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.spotifree.app.data.db.AppDatabase
import com.spotifree.app.data.db.BlockedArtistEntity
import com.spotifree.app.data.db.BlockedTrackEntity
import com.spotifree.app.data.db.FollowedArtistEntity
import com.spotifree.app.data.db.HistoryEntity
import com.spotifree.app.data.db.LikeEntity
import com.spotifree.app.data.db.PlaylistEntity
import com.spotifree.app.data.db.PlaylistTrackEntity
import com.spotifree.app.data.db.TrackEntity
import com.spotifree.app.data.library.LibraryRepository
import com.spotifree.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

data class ImportedPlaylist(val title: String, val tracks: List<Track>, val skippedItems: Int)

data class SpotifyAnalysis(
    val playlists: List<ImportedPlaylist> = emptyList(),
    val liked: List<Track> = emptyList(),
    val artists: List<String> = emptyList(),
    val bannedTracks: List<Track> = emptyList(),
    val bannedArtists: List<String> = emptyList(),
    val history: List<HistoryEntity> = emptyList(),
    val historyKind: String? = null, // "extended" | "basic"
    val recognized: List<String> = emptyList(),
    val ignored: List<String> = emptyList(),
) {
    val uniqueTracks: Int get() = (liked.map { it.id } + playlists.flatMap { p -> p.tracks.map { it.id } }).toSet().size
    val historyFrom: Long? get() = history.minOfOrNull { it.playedAt }
    val historyTo: Long? get() = history.maxOfOrNull { it.playedAt }
    val isEmpty: Boolean get() = playlists.isEmpty() && liked.isEmpty() && artists.isEmpty() && history.isEmpty() && bannedTracks.isEmpty() && bannedArtists.isEmpty()
}

data class SpotifyImportOptions(
    val playlists: Boolean = true,
    val liked: Boolean = true,
    val artists: Boolean = true,
    val history: Boolean = true,
    val banned: Boolean = true,
)

data class SpotifyImportResult(
    val playlistsAdded: Int,
    val playlistsSkipped: Int,
    val likedAdded: Int,
    val artistsAdded: Int,
    val blockedAdded: Int,
    val historyAdded: Int,
)

/**
 * Spotify's "Download your data" export (port of the previous app): account data ZIP
 * (YourLibrary.json, Playlist*.json, StreamingHistory_music_*.json) and the extended streaming
 * history ZIP (Streaming_History_Audio_*.json). Files are recognised by their content, so loose
 * or renamed JSON files work too. ZIPs are streamed: nothing is held in memory twice.
 */
class SpotifyImporter(private val context: Context, private val db: AppDatabase, private val library: LibraryRepository) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(uris: List<Uri>, onProgress: (String) -> Unit = {}): SpotifyAnalysis = withContext(Dispatchers.IO) {
        val docs = ArrayList<Pair<String, JsonElement>>()
        val ignored = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                if (name.endsWith(".zip", true) || looksLikeZip(uri)) {
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory && entry.name.endsWith(".json", true) && !entry.name.contains("__MACOSX")) {
                                onProgress(entry.name.substringAfterLast('/'))
                                parse(zip, entry.name)?.let { docs += entry.name to it } ?: run { ignored += entry.name }
                            }
                        }
                    }
                } else {
                    onProgress(name)
                    parse(input, name)?.let { docs += name to it } ?: run { ignored += name }
                }
            }
        }
        analyzeDocs(docs).let { it.copy(ignored = it.ignored + ignored) }
    }

    private fun parse(input: InputStream, name: String): JsonElement? = runCatchingCancellable {
        val text = input.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
        json.parseToJsonElement(text)
    }.getOrNull()

    private fun looksLikeZip(uri: Uri): Boolean = runCatchingCancellable {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(2)
            s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        } ?: false
    }.getOrDefault(false)

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "archivo"
        }
        return uri.lastPathSegment ?: "archivo"
    }

    // ------------------------------------------------------------------ analysis

    private fun JsonObject.s(k: String): String? = this[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }
    private fun JsonObject.l(k: String): Long? = this[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.longOrNull }

    private fun kindOf(data: JsonElement): String? {
        if (data is JsonArray) {
            val sample = data.firstOrNull { it is JsonObject } as? JsonObject ?: return null
            if ("ts" in sample && "ms_played" in sample) return "extended"
            if ("endTime" in sample && "msPlayed" in sample) {
                if ("podcastName" in sample || "episodeName" in sample) return "podcast"
                if ("trackName" in sample) return "basic"
            }
            return null
        }
        if (data is JsonObject) {
            if (data["playlists"] is JsonArray) return "playlists"
            if (data["tracks"] is JsonArray && (data["artists"] is JsonArray || data["albums"] is JsonArray || data["bannedTracks"] is JsonArray)) return "library"
        }
        return null
    }

    fun analyzeDocs(docs: List<Pair<String, JsonElement>>): SpotifyAnalysis {
        val playlists = ArrayList<ImportedPlaylist>()
        val liked = ArrayList<Track>()
        val artists = ArrayList<String>()
        val bannedTracks = ArrayList<Track>()
        val bannedArtists = ArrayList<String>()
        val extended = ArrayList<JsonObject>()
        val basic = ArrayList<JsonObject>()
        val recognized = ArrayList<String>()
        val ignored = ArrayList<String>()
        val idByKey = HashMap<String, String>()
        fun remember(t: Track): Track {
            if (!t.id.startsWith("spotify-h-")) idByKey.putIfAbsent(key(t.artist, t.title), t.id)
            return t
        }
        for ((name, data) in docs) {
            when (kindOf(data)) {
                null, "podcast" -> ignored += name
                "library" -> {
                    recognized += name
                    val o = data as JsonObject
                    (o["tracks"] as? JsonArray)?.forEach { e ->
                        val t = e as? JsonObject ?: return@forEach
                        val tn = t.s("track")
                        val an = t.s("artist")
                        if (tn != null && an != null) liked += remember(makeTrack(tn, an, t.s("album"), t.s("uri"), null))
                    }
                    (o["artists"] as? JsonArray)?.forEach { e -> nameOf(e)?.let { artists += it } }
                    (o["bannedTracks"] as? JsonArray)?.forEach { e ->
                        val t = e as? JsonObject ?: return@forEach
                        val tn = t.s("track")
                        val an = t.s("artist")
                        if (tn != null && an != null) bannedTracks += remember(makeTrack(tn, an, t.s("album"), t.s("uri"), null))
                    }
                    (o["bannedArtists"] as? JsonArray)?.forEach { e -> nameOf(e)?.let { bannedArtists += it } }
                }
                "playlists" -> {
                    recognized += name
                    ((data as JsonObject)["playlists"] as JsonArray).forEach { pe ->
                        val pl = pe as? JsonObject ?: return@forEach
                        val tracks = ArrayList<Track>()
                        var skipped = 0
                        (pl["items"] as? JsonArray)?.forEach { ie ->
                            val tr = (ie as? JsonObject)?.get("track") as? JsonObject
                            val tn = tr?.s("trackName")
                            val an = tr?.s("artistName")
                            if (tn != null && an != null) tracks += remember(makeTrack(tn, an, tr.s("albumName"), tr.s("trackUri"), null))
                            else skipped++
                        }
                        val title = pl.s("name")?.trim().takeUnless { it.isNullOrEmpty() } ?: "Playlist de Spotify"
                        playlists += ImportedPlaylist(title, tracks.distinctBy { it.id }, skipped)
                    }
                }
                "extended" -> {
                    recognized += name
                    (data as JsonArray).forEach { (it as? JsonObject)?.let(extended::add) }
                }
                "basic" -> {
                    recognized += name
                    (data as JsonArray).forEach { (it as? JsonObject)?.let(basic::add) }
                }
            }
        }
        val history = ArrayList<HistoryEntity>()
        var kind: String? = null
        if (extended.isNotEmpty()) {
            kind = "extended"
            for (e in extended) {
                val title = e.s("master_metadata_track_name") ?: continue
                val artist = e.s("master_metadata_album_artist_name") ?: continue
                val ms = e.l("ms_played") ?: 0
                if (ms < MIN_STREAM_MS) continue
                val end = runCatchingCancellable { Instant.parse(e.s("ts")).toEpochMilli() }.getOrNull() ?: continue
                val t = remember(makeTrack(title, artist, e.s("master_metadata_album_album_name"), e.s("spotify_track_uri"), null))
                history += HistoryEntity(trackId = t.id, title = t.title, artist = t.artist, playedAt = end - ms, secondsPlayed = (ms / 1000).toInt(), source = "spotify")
            }
        } else if (basic.isNotEmpty()) {
            kind = "basic"
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            for (e in basic) {
                val ms = e.l("msPlayed") ?: 0
                val tn = e.s("trackName") ?: continue
                val an = e.s("artistName") ?: continue
                if (ms < MIN_STREAM_MS) continue
                val end = runCatchingCancellable { LocalDateTime.parse(e.s("endTime"), fmt).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: continue
                val fallback = makeTrack(tn, an, null, null, null)
                history += HistoryEntity(trackId = idByKey[key(an, tn)] ?: fallback.id, title = fallback.title, artist = fallback.artist, playedAt = end - ms, secondsPlayed = (ms / 1000).toInt(), source = "spotify")
            }
        }
        history.sortBy { it.playedAt }
        return SpotifyAnalysis(
            playlists = playlists,
            liked = liked.distinctBy { it.id },
            artists = artists.distinctBy { norm(it) },
            bannedTracks = bannedTracks.distinctBy { it.id },
            bannedArtists = bannedArtists.distinctBy { norm(it) },
            history = history,
            historyKind = kind,
            recognized = recognized,
            ignored = ignored,
        )
    }

    private fun nameOf(e: JsonElement): String? = when (e) {
        is JsonObject -> e.s("name")
        is JsonArray -> null
        else -> e.jsonPrimitive.contentOrNull
    }?.trim()?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ import

    suspend fun import(a: SpotifyAnalysis, o: SpotifyImportOptions, onProgress: (String) -> Unit = {}): SpotifyImportResult = withContext(Dispatchers.IO) {
        var playlistsAdded = 0
        var playlistsSkipped = 0
        var likedAdded = 0
        var artistsAdded = 0
        var blockedAdded = 0
        var historyAdded = 0
        db.withTransaction {
            val tracks = LinkedHashMap<String, Track>()
            if (o.playlists) a.playlists.forEach { p -> p.tracks.forEach { tracks.putIfAbsent(it.id, it) } }
            if (o.liked) a.liked.forEach { tracks.putIfAbsent(it.id, it) }
            if (o.banned) a.bannedTracks.forEach { tracks.putIfAbsent(it.id, it) }
            tracks.values.chunked(500).forEach { chunk -> db.tracks().insertIgnore(chunk.map { TrackEntity.from(it) }) }

            if (o.playlists) {
                onProgress("playlists")
                val existing = db.playlists().all().filter { it.playlist.isSpotifyImport }.map { it.playlist.title.trim().lowercase() }.toMutableSet()
                val now = System.currentTimeMillis()
                a.playlists.forEachIndexed { i, p ->
                    if (p.tracks.isEmpty()) return@forEachIndexed
                    if (!existing.add(p.title.trim().lowercase())) {
                        playlistsSkipped++
                        return@forEachIndexed
                    }
                    val id = "pl-spotify-$now-$i"
                    db.playlists().upsert(PlaylistEntity(id = id, title = p.title, isSpotifyImport = true, sortIndex = now - i))
                    db.playlists().insertTracks(p.tracks.mapIndexed { idx, t -> PlaylistTrackEntity(id, t.id, idx + 1L) })
                    playlistsAdded++
                }
            }
            if (o.liked) {
                onProgress("liked")
                val already = db.likes().likedIds().toHashSet()
                // Appended after the current likes, Spotify order kept (first = most recent)
                val base = IMPORTED_LIKES_BASE
                val toAdd = a.liked.filter { it.id !in already }
                db.likes().insertAll(toAdd.mapIndexed { i, t -> LikeEntity(t.id, base - i) })
                likedAdded = toAdd.size
            }
            if (o.artists) {
                val followed = db.artists().followed().map { it.key }.toHashSet()
                a.artists.forEach { name ->
                    val key = name.trim().lowercase()
                    if (followed.add(key)) {
                        db.artists().follow(FollowedArtistEntity(key = key, name = name.trim()))
                        artistsAdded++
                    }
                }
            }
            if (o.banned) {
                val blockedT = db.blocks().trackIds().toHashSet()
                a.bannedTracks.forEach { t -> if (blockedT.add(t.id)) { db.blocks().blockTrack(BlockedTrackEntity(t.id, t.title, t.artist)); blockedAdded++ } }
                val blockedA = db.blocks().artists().map { it.key }.toHashSet()
                a.bannedArtists.forEach { n ->
                    val key = n.trim().lowercase()
                    if (blockedA.add(key)) { db.blocks().blockArtist(BlockedArtistEntity(key, key)); blockedAdded++ }
                }
            }
        }
        if (o.history && a.history.isNotEmpty()) {
            onProgress("history")
            // A newer export replaces the previous Spotify history (no duplicates)
            db.history().deleteSource("spotify")
            a.history.chunked(2000).forEach { db.history().insertAll(it) }
            historyAdded = a.history.size
            val count = db.history().count()
            if (count > LibraryRepository.HISTORY_MAX) db.history().deleteOldest(count - LibraryRepository.HISTORY_MAX)
        }
        SpotifyImportResult(playlistsAdded, playlistsSkipped, likedAdded, artistsAdded, blockedAdded, historyAdded)
    }

    companion object {
        private const val MIN_STREAM_MS = 30_000L
        /** Imported likes sort after the ones made in the app (older timestamps, order kept). */
        const val IMPORTED_LIKES_BASE = 1_000_000_000_000L

        // Same normalization as the previous app (ids of imported songs must not change)
        fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("[\u0300-\u036f]"), "")
            .replace(Regex("""\s+"""), " ").trim()

        fun key(artist: String, title: String) = "${norm(artist)}|${norm(title)}"

        private fun fnv1a(str: String): String {
            var h = 0x811c9dc5.toInt()
            for (c in str) {
                h = h xor c.code
                h *= 0x01000193
            }
            return (h.toLong() and 0xffffffffL).toString(36)
        }

        /** Same ids as the previous app: "spotify-<spotifyId>" or "spotify-h-<hash of artist|title>". */
        fun makeTrack(title: String, artist: String, album: String?, uri: String?, durationMs: Long?): Track {
            val sid = Regex("""^spotify:track:([A-Za-z0-9]{10,})$""").find(uri?.trim().orEmpty())?.groupValues?.get(1)
            val t = title.trim()
            val a = artist.trim()
            return Track(
                id = if (sid != null) "spotify-$sid" else "spotify-h-${fnv1a(key(a, t))}",
                title = t,
                artist = a,
                album = album?.trim().orEmpty(),
                durationSec = durationMs?.let { (it / 1000).toInt() } ?: 0,
                searchQuery = "$a $t".trim(),
            )
        }
    }
}
