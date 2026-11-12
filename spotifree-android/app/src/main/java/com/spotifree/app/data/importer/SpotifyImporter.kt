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
class SpotifyImporter(private val context: Context? = null, private val db: AppDatabase? = null, private val library: LibraryRepository? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(uris: List<Uri>, onProgress: (String) -> Unit = {}): SpotifyAnalysis = withContext(Dispatchers.IO) {
        val ctx = context ?: error("Context required for Uri analysis")
        val builder = AnalysisBuilder()
        for (uri in uris) {
            val name = displayName(ctx, uri)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    analyzeStream(input, name, builder, onProgress)
                }
            } catch (t: Throwable) {
                android.util.Log.e("SpotifyImporter", "Unable to read uri: $uri ($name)", t)
            }
        }
        builder.build()
    }

    internal fun analyzeStream(
        input: InputStream,
        name: String,
        builder: AnalysisBuilder,
        onProgress: (String) -> Unit = {}
    ) {
        val buffered = if (input.markSupported()) input else input.buffered()
        if (name.endsWith(".zip", true) || isZipStream(buffered)) {
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        val entryName = entry.name
                        if (!entry.isDirectory && !shouldSkipZipEntry(entryName)) {
                            onProgress(entryName.substringAfterLast('/'))
                            val element = parse(zip, entryName)
                            if (element != null) {
                                builder.process(entryName, element)
                            } else {
                                builder.ignored += entryName
                            }
                        }
                    } finally {
                        runCatching { zip.closeEntry() }
                    }
                }
            }
        } else if (name.endsWith(".json", true)) {
            onProgress(name)
            val element = parse(buffered, name)
            if (element != null) {
                builder.process(name, element)
            } else {
                builder.ignored += name
            }
        }
    }

    fun analyzeStreams(streams: List<Pair<String, InputStream>>, onProgress: (String) -> Unit = {}): SpotifyAnalysis {
        val builder = AnalysisBuilder()
        for ((name, input) in streams) {
            analyzeStream(input, name, builder, onProgress)
        }
        return builder.build()
    }

    private fun parse(input: InputStream, name: String): JsonElement? = runCatchingCancellable {
        val nonClosing = object : java.io.FilterInputStream(input) {
            override fun close() {
                // Do not close underlying stream; ZipInputStream must remain open for next entries
            }
        }
        val pushback = java.io.PushbackInputStream(nonClosing, 3)
        val bom = ByteArray(3)
        val read = pushback.read(bom, 0, 3)
        if (read == 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()) {
            // BOM skipped
        } else if (read > 0) {
            pushback.unread(bom, 0, read)
        }
        val text = java.io.InputStreamReader(pushback, Charsets.UTF_8).use { it.readText() }
        json.parseToJsonElement(text)
    }.getOrNull()

    private fun isZipStream(input: InputStream): Boolean {
        if (!input.markSupported()) return false
        input.mark(4)
        val b = ByteArray(2)
        val read = input.read(b)
        input.reset()
        return read == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "archivo"
        }
        return uri.lastPathSegment ?: "archivo"
    }

    // ------------------------------------------------------------------ analysis

    fun analyzeDocs(docs: List<Pair<String, JsonElement>>): SpotifyAnalysis {
        val builder = AnalysisBuilder()
        for ((name, data) in docs) {
            builder.process(name, data)
        }
        return builder.build()
    }

    internal class AnalysisBuilder {
        val playlists = ArrayList<ImportedPlaylist>()
        val liked = ArrayList<Track>()
        val artists = ArrayList<String>()
        val bannedTracks = ArrayList<Track>()
        val bannedArtists = ArrayList<String>()
        val extended = ArrayList<HistoryEntity>()
        val basic = ArrayList<HistoryEntity>()
        val recognized = ArrayList<String>()
        val ignored = ArrayList<String>()

        private val idByKey = HashMap<String, String>()
        private val seenPlaylistTitles = HashMap<String, Int>()
        private val seenLikedIds = HashSet<String>()
        private val seenArtists = HashSet<String>()
        private val seenBannedTrackIds = HashSet<String>()
        private val seenBannedArtists = HashSet<String>()
        private val seenExtendedStreams = HashSet<String>()
        private val seenBasicStreams = HashSet<String>()

        private fun remember(t: Track): Track {
            if (!t.id.startsWith("spotify-h-")) idByKey.putIfAbsent(key(t.artist, t.title), t.id)
            return t
        }

        fun process(name: String, data: JsonElement) {
            when (kindOf(data)) {
                null, "podcast" -> ignored += name
                "library" -> {
                    recognized += name
                    val o = data as? JsonObject ?: return
                    (o["tracks"] as? JsonArray)?.forEach { e ->
                        val t = e as? JsonObject ?: return@forEach
                        val tn = t.s("track")
                        val an = t.s("artist")
                        if (tn != null && an != null) {
                            val track = remember(makeTrack(tn, an, t.s("album"), t.s("uri"), null))
                            if (seenLikedIds.add(track.id)) liked += track
                        }
                    }
                    (o["artists"] as? JsonArray)?.forEach { e ->
                        nameOf(e)?.let { raw ->
                            if (seenArtists.add(norm(raw))) artists += raw
                        }
                    }
                    (o["bannedTracks"] as? JsonArray)?.forEach { e ->
                        val t = e as? JsonObject ?: return@forEach
                        val tn = t.s("track")
                        val an = t.s("artist")
                        if (tn != null && an != null) {
                            val track = remember(makeTrack(tn, an, t.s("album"), t.s("uri"), null))
                            if (seenBannedTrackIds.add(track.id)) bannedTracks += track
                        }
                    }
                    (o["bannedArtists"] as? JsonArray)?.forEach { e ->
                        nameOf(e)?.let { raw ->
                            if (seenBannedArtists.add(norm(raw))) bannedArtists += raw
                        }
                    }
                }
                "playlists" -> {
                    recognized += name
                    ((data as? JsonObject)?.get("playlists") as? JsonArray)?.forEach { pe ->
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
                        val distinctTracks = tracks.distinctBy { it.id }
                        val key = title.lowercase()
                        val existingIdx = seenPlaylistTitles[key]
                        if (existingIdx != null) {
                            if (distinctTracks.size > playlists[existingIdx].tracks.size) {
                                playlists[existingIdx] = ImportedPlaylist(title, distinctTracks, skipped)
                            }
                        } else {
                            seenPlaylistTitles[key] = playlists.size
                            playlists += ImportedPlaylist(title, distinctTracks, skipped)
                        }
                    }
                }
                "extended" -> {
                    recognized += name
                    (data as? JsonArray)?.forEach { item ->
                        val e = item as? JsonObject ?: return@forEach
                        val title = e.s("master_metadata_track_name") ?: return@forEach
                        val artist = e.s("master_metadata_album_artist_name") ?: return@forEach
                        val ms = e.l("ms_played") ?: 0
                        if (ms < MIN_STREAM_MS) return@forEach
                        val end = runCatchingCancellable { Instant.parse(e.s("ts")).toEpochMilli() }.getOrNull() ?: return@forEach
                        val t = remember(makeTrack(title, artist, e.s("master_metadata_album_album_name"), e.s("spotify_track_uri"), null))
                        val playedAt = end - ms
                        val dedupKey = "$playedAt:${t.id}"
                        if (seenExtendedStreams.add(dedupKey)) {
                            extended += HistoryEntity(
                                trackId = t.id,
                                title = t.title,
                                artist = t.artist,
                                playedAt = playedAt,
                                secondsPlayed = (ms / 1000).toInt(),
                                source = "spotify"
                            )
                        }
                    }
                }
                "basic" -> {
                    recognized += name
                    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    (data as? JsonArray)?.forEach { item ->
                        val e = item as? JsonObject ?: return@forEach
                        val ms = e.l("msPlayed") ?: 0
                        val tn = e.s("trackName") ?: return@forEach
                        val an = e.s("artistName") ?: return@forEach
                        if (ms < MIN_STREAM_MS) return@forEach
                        val end = runCatchingCancellable { LocalDateTime.parse(e.s("endTime"), fmt).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return@forEach
                        val fallback = makeTrack(tn, an, null, null, null)
                        val playedAt = end - ms
                        val trackId = idByKey[key(an, tn)] ?: fallback.id
                        val dedupKey = "$playedAt:$trackId"
                        if (seenBasicStreams.add(dedupKey)) {
                            basic += HistoryEntity(
                                trackId = trackId,
                                title = fallback.title,
                                artist = fallback.artist,
                                playedAt = playedAt,
                                secondsPlayed = (ms / 1000).toInt(),
                                source = "spotify"
                            )
                        }
                    }
                }
            }
        }

        fun build(): SpotifyAnalysis {
            val history: List<HistoryEntity>
            val kind: String?
            if (extended.isNotEmpty()) {
                kind = "extended"
                history = extended.apply { sortBy { it.playedAt } }
            } else if (basic.isNotEmpty()) {
                history = basic.map { h ->
                    val betterId = idByKey[key(h.artist, h.title)]
                    if (betterId != null && betterId != h.trackId) h.copy(trackId = betterId) else h
                }.sortedBy { it.playedAt }
                kind = "basic"
            } else {
                history = emptyList()
                kind = null
            }
            return SpotifyAnalysis(
                playlists = playlists,
                liked = liked,
                artists = artists,
                bannedTracks = bannedTracks,
                bannedArtists = bannedArtists,
                history = history,
                historyKind = kind,
                recognized = recognized,
                ignored = ignored,
            )
        }
    }

    // ------------------------------------------------------------------ import

    suspend fun import(a: SpotifyAnalysis, o: SpotifyImportOptions, onProgress: (String) -> Unit = {}): SpotifyImportResult = withContext(Dispatchers.IO) {
        val database = db ?: error("Database required for import")
        var playlistsAdded = 0
        var playlistsSkipped = 0
        var likedAdded = 0
        var artistsAdded = 0
        var blockedAdded = 0
        var historyAdded = 0
        database.withTransaction {
            val tracks = LinkedHashMap<String, Track>()
            if (o.playlists) a.playlists.forEach { p -> p.tracks.forEach { tracks.putIfAbsent(it.id, it) } }
            if (o.liked) a.liked.forEach { tracks.putIfAbsent(it.id, it) }
            if (o.banned) a.bannedTracks.forEach { tracks.putIfAbsent(it.id, it) }
            if (o.history) {
                a.history.forEach { h ->
                    tracks.putIfAbsent(h.trackId, Track(
                        id = h.trackId,
                        title = h.title,
                        artist = h.artist,
                        album = "",
                        durationSec = h.secondsPlayed,
                        searchQuery = "${h.artist} ${h.title}".trim()
                    ))
                }
            }
            tracks.values.chunked(500).forEach { chunk -> database.tracks().insertIgnore(chunk.map { TrackEntity.from(it) }) }

            if (o.playlists) {
                onProgress("playlists")
                val existing = database.playlists().all().filter { it.playlist.isSpotifyImport }.map { it.playlist.title.trim().lowercase() }.toMutableSet()
                val now = System.currentTimeMillis()
                a.playlists.forEachIndexed { i, p ->
                    if (p.tracks.isEmpty()) return@forEachIndexed
                    if (!existing.add(p.title.trim().lowercase())) {
                        playlistsSkipped++
                        return@forEachIndexed
                    }
                    val id = "pl-spotify-$now-$i"
                    database.playlists().upsert(PlaylistEntity(id = id, title = p.title, isSpotifyImport = true, sortIndex = now - i))
                    database.playlists().insertTracks(p.tracks.mapIndexed { idx, t -> PlaylistTrackEntity(id, t.id, idx + 1L) })
                    playlistsAdded++
                }
            }
            if (o.liked) {
                onProgress("liked")
                val already = database.likes().likedIds().toHashSet()
                // Appended after the current likes, Spotify order kept (first = most recent)
                val base = IMPORTED_LIKES_BASE
                val toAdd = a.liked.filter { it.id !in already }
                database.likes().insertAll(toAdd.mapIndexed { i, t -> LikeEntity(t.id, base - i) })
                likedAdded = toAdd.size
            }
            if (o.artists) {
                val followed = database.artists().followed().map { it.key }.toHashSet()
                a.artists.forEach { name ->
                    val key = name.trim().lowercase()
                    if (followed.add(key)) {
                        database.artists().follow(FollowedArtistEntity(key = key, name = name.trim()))
                        artistsAdded++
                    }
                }
            }
            if (o.banned) {
                val blockedT = database.blocks().trackIds().toHashSet()
                a.bannedTracks.forEach { t -> if (blockedT.add(t.id)) { database.blocks().blockTrack(BlockedTrackEntity(t.id, t.title, t.artist)); blockedAdded++ } }
                val blockedA = database.blocks().artists().map { it.key }.toHashSet()
                a.bannedArtists.forEach { n ->
                    val key = n.trim().lowercase()
                    if (blockedA.add(key)) { database.blocks().blockArtist(BlockedArtistEntity(key, key)); blockedAdded++ }
                }
            }
        }
        if (o.history && a.history.isNotEmpty()) {
            onProgress("history")
            // A newer export replaces the previous Spotify history (no duplicates)
            database.history().deleteSource("spotify")
            a.history.chunked(500).forEach { database.history().insertAll(it) }
            historyAdded = a.history.size
            val count = database.history().count()
            if (count > LibraryRepository.HISTORY_MAX) database.history().deleteOldest(count - LibraryRepository.HISTORY_MAX)
        }
        SpotifyImportResult(playlistsAdded, playlistsSkipped, likedAdded, artistsAdded, blockedAdded, historyAdded)
    }

    companion object {
        private const val MIN_STREAM_MS = 30_000L
        /** Imported likes sort after the ones made in the app (older timestamps, order kept). */
        const val IMPORTED_LIKES_BASE = 1_000_000_000_000L

        private val SKIPPED_EXACT_NAMES = setOf(
            "searchqueries.json",
            "inferences.json",
            "userattributes.json",
            "userprompts.json",
            "identity.json",
            "identifiers.json",
            "payments.json",
            "marquee.json",
            "idjaudio.json",
            "adsidentitysecondpartyidentifiers.json",
            "agentgateway.json"
        )

        fun shouldSkipZipEntry(entryName: String): Boolean {
            val lower = entryName.lowercase()
            if (!lower.endsWith(".json")) return true
            if (lower.contains("__macosx") || lower.contains(".ds_store")) return true
            if (lower.contains("technical log information") || lower.contains("technical log")) return true
            val simple = lower.substringAfterLast('/')
            return simple in SKIPPED_EXACT_NAMES
        }

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

        private fun nameOf(e: JsonElement): String? = when (e) {
            is JsonObject -> e.s("name")
            is JsonArray -> null
            else -> e.jsonPrimitive.contentOrNull
        }?.trim()?.takeIf { it.isNotEmpty() }

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
