package com.spotifree.app.data.library

import com.spotifree.app.util.runCatchingCancellable

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
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.remote.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/** Result of adding a song to a playlist (the UI shows "added" / "already there"). */
enum class AddResult { ADDED, ALREADY_THERE, FAILED }

data class PlaylistSnapshot(val playlist: PlaylistEntity, val trackIds: List<String>)

/**
 * The user's library: songs, likes, playlists, followed and blocked artists, history.
 * Everything lives in Room, so a library of tens of thousands of songs costs nothing to keep.
 */
class LibraryRepository(
    private val db: AppDatabase,
    private val music: MusicRepository,
    scope: CoroutineScope,
) {
    private val tracks = db.tracks()
    private val likes = db.likes()
    private val playlists = db.playlists()

    val likedIds: StateFlow<Set<String>> = likes.observeLikedIds().map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val blockedArtists: StateFlow<Set<String>> = db.blocks().observeArtists().map { list -> list.map { it.key }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val blockedTracks: StateFlow<Set<String>> = db.blocks().observeTracks().map { list -> list.map { it.trackId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun observeLiked(): Flow<List<Track>> = likes.observeLikedTracks().map { list -> list.map { it.toTrack() } }

    fun observePlaylists(): Flow<List<Playlist>> = playlists.observeAll().map { list ->
        list.map { it.playlist.toPlaylist(it.count) }
    }

    fun observePlaylist(id: String): Flow<Playlist?> = playlists.observe(id).map { it?.playlist?.toPlaylist(it.count) }

    fun observePlaylistTracks(id: String): Flow<List<Track>> =
        if (id == Playlist.LIKED_ID) observeLiked()
        else playlists.observeTracks(id).map { list -> list.map { it.toTrack() } }

    fun observeFollowedArtists() = db.artists().observeFollowed()

    fun observeRecent(limit: Int = 20): Flow<List<Track>> = db.history().observeRecentDistinct(limit).map { list ->
        list.map { Track(id = it.trackId, title = it.title, artist = it.artist, coverUrl = it.coverUrl) }
    }

    suspend fun playlistTracks(id: String): List<Track> =
        if (id == Playlist.LIKED_ID) likes.likedTracks().map { it.toTrack() } else playlists.tracks(id).map { it.toTrack() }

    suspend fun allPlaylists(): List<Playlist> = playlists.all().map { it.playlist.toPlaylist(it.count) }

    suspend fun track(id: String): Track? = tracks.get(id)?.toTrack()

    suspend fun tracksByIds(ids: List<String>): List<Track> {
        val byId = HashMap<String, Track>()
        ids.distinct().chunked(900).forEach { chunk -> tracks.getMany(chunk).forEach { byId[it.id] = it.toTrack() } }
        return ids.mapNotNull { byId[it] }
    }

    /** Keeps a song in the library. Known songs keep their data; a newly resolved video id is stored. */
    suspend fun save(track: Track) {
        val existing = tracks.get(track.id)
        if (existing == null) {
            tracks.upsert(TrackEntity.from(track))
        } else if (existing.videoId == null) {
            track.youtubeId?.let { vid -> tracks.setResolved(track.id, vid, track.coverUrl, track.durationSec) }
        }
    }

    suspend fun saveAll(list: List<Track>) {
        if (list.isEmpty()) return
        list.chunked(500).forEach { chunk -> tracks.insertIgnore(chunk.map { TrackEntity.from(it) }) }
    }

    suspend fun isLiked(trackId: String) = likes.isLiked(trackId)

    /** @return true when the song is now liked */
    suspend fun toggleLike(track: Track): Boolean {
        return if (likes.isLiked(track.id)) {
            likes.delete(track.id)
            false
        } else {
            save(track)
            likes.insert(LikeEntity(track.id))
            true
        }
    }

    suspend fun setLiked(track: Track, liked: Boolean) {
        if (liked) {
            save(track)
            likes.insert(LikeEntity(track.id))
        } else likes.delete(track.id)
    }

    suspend fun createPlaylist(
        title: String,
        tracksToAdd: List<Track> = emptyList(),
        id: String = "pl-${System.currentTimeMillis()}",
        youtubePlaylistId: String? = null,
        isSpotifyImport: Boolean = false,
        coverUrl: String? = null,
    ): String {
        db.withTransaction {
            playlists.upsert(
                PlaylistEntity(
                    id = id, title = title.trim(), coverUrl = coverUrl ?: tracksToAdd.firstOrNull()?.artworkUrl,
                    youtubePlaylistId = youtubePlaylistId, isSpotifyImport = isSpotifyImport,
                )
            )
            if (tracksToAdd.isNotEmpty()) {
                saveAll(tracksToAdd)
                playlists.replaceTracks(id, tracksToAdd.map { it.id })
            }
        }
        return id
    }

    /** Re-reads a playlist imported from YouTube and replaces its songs. */
    suspend fun replacePlaylistTracks(id: String, tracksToSet: List<Track>) {
        db.withTransaction {
            saveAll(tracksToSet)
            playlists.replaceTracks(id, tracksToSet.map { it.id })
        }
    }

    suspend fun deletePlaylist(id: String): PlaylistSnapshot? {
        if (id == Playlist.LIKED_ID) return null
        val p = playlists.get(id) ?: return null
        val ids = playlists.trackIds(id)
        db.withTransaction {
            playlists.clearTracks(id)
            playlists.delete(id)
        }
        return PlaylistSnapshot(p, ids)
    }

    suspend fun restorePlaylist(s: PlaylistSnapshot) {
        db.withTransaction {
            playlists.upsert(s.playlist)
            playlists.replaceTracks(s.playlist.id, s.trackIds)
        }
    }

    suspend fun renamePlaylist(id: String, title: String) {
        if (title.isNotBlank()) playlists.rename(id, title.trim())
    }

    suspend fun addToPlaylist(playlistId: String, track: Track): AddResult {
        if (playlistId == Playlist.LIKED_ID) {
            if (likes.isLiked(track.id)) return AddResult.ALREADY_THERE
            setLiked(track, true)
            return AddResult.ADDED
        }
        val p = playlists.get(playlistId) ?: return AddResult.FAILED
        if (playlists.contains(playlistId, track.id)) return AddResult.ALREADY_THERE
        save(track)
        playlists.insertTracks(listOf(PlaylistTrackEntity(playlistId, track.id, playlists.maxPosition(playlistId) + 1)))
        if (p.coverUrl.isNullOrBlank()) playlists.setCover(playlistId, track.artworkUrl)
        return AddResult.ADDED
    }

    /** @return the removed position (for "undo") or null */
    suspend fun removeFromPlaylist(playlistId: String, trackId: String): Int? {
        if (playlistId == Playlist.LIKED_ID) {
            val idx = likes.likedIds().indexOf(trackId)
            if (idx < 0) return null
            likes.delete(trackId)
            return idx
        }
        val ids = playlists.trackIds(playlistId)
        val idx = ids.indexOf(trackId)
        if (idx < 0) return null
        playlists.removeTrack(playlistId, trackId)
        return idx
    }

    suspend fun restoreToPlaylist(playlistId: String, trackId: String, index: Int) {
        if (playlistId == Playlist.LIKED_ID) {
            likes.insert(LikeEntity(trackId))
            return
        }
        val ids = playlists.trackIds(playlistId).toMutableList()
        if (trackId in ids) return
        ids.add(index.coerceIn(0, ids.size), trackId)
        playlists.replaceTracks(playlistId, ids)
    }

    suspend fun movePlaylistTrack(playlistId: String, from: Int, to: Int) {
        val ids = playlists.trackIds(playlistId).toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        playlists.replaceTracks(playlistId, ids)
    }

    /** Likes every song of a playlist, or unlikes them all when all are already liked. */
    suspend fun toggleLikeAll(playlistId: String): Boolean {
        val ids = playlists.trackIds(playlistId)
        if (ids.isEmpty()) return false
        val liked = likedIds.value
        return if (ids.all { it in liked }) {
            ids.chunked(900).forEach { likes.deleteMany(it) }
            false
        } else {
            val now = System.currentTimeMillis()
            likes.insertAll(ids.mapIndexed { i, id -> LikeEntity(id, now - i) })
            true
        }
    }

    // ------------------------------------------------------------------ artists

    fun artistKey(name: String) = name.trim().lowercase()

    fun observeIsFollowed(name: String) = db.artists().observeIsFollowed(artistKey(name))

    suspend fun toggleFollow(name: String, imageUrl: String?, url: String?): Boolean {
        val key = artistKey(name)
        val followed = db.artists().followed().any { it.key == key }
        if (followed) db.artists().unfollow(key)
        else db.artists().follow(FollowedArtistEntity(key = key, name = name.trim(), imageUrl = imageUrl, url = url))
        return !followed
    }

    // ------------------------------------------------------------------ blocklist

    fun isBlocked(track: Track): Boolean {
        if (track.id in blockedTracks.value) return true
        val artists = blockedArtists.value
        if (artists.isEmpty()) return false
        if (track.artist.trim().lowercase() in artists) return true
        return splitArtists(track.artist).any { it in artists }
    }

    suspend fun blockArtist(nameOrArtistLine: String): String? {
        val name = splitArtists(nameOrArtistLine).firstOrNull() ?: nameOrArtistLine.trim().lowercase()
        if (name.isBlank()) return null
        db.blocks().blockArtist(BlockedArtistEntity(name, name))
        return name
    }

    suspend fun unblockArtist(key: String) = db.blocks().unblockArtist(key.trim().lowercase())

    suspend fun blockTrack(track: Track) = db.blocks().blockTrack(BlockedTrackEntity(track.id, track.title, track.artist))

    suspend fun unblockTrack(trackId: String) = db.blocks().unblockTrack(trackId)

    fun observeBlockedArtists() = db.blocks().observeArtists()
    fun observeBlockedTracks() = db.blocks().observeTracks()

    // ------------------------------------------------------------------ history

    suspend fun recordPlay(track: Track, seconds: Int) {
        if (seconds < 5) return
        db.history().insert(
            HistoryEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                coverUrl = track.artworkUrl,
                playedAt = System.currentTimeMillis() - seconds * 1000L,
                secondsPlayed = seconds,
            )
        )
        val count = db.history().count()
        if (count > HISTORY_MAX) db.history().deleteOldest(count - HISTORY_MAX)
    }

    // ------------------------------------------------------------------ YouTube lookup for imports

    private val resolving = ConcurrentHashMap<String, Boolean>()

    /**
     * Finds the YouTube version of a song that has none yet (Spotify / CSV imports) and stores it.
     * @return the updated song, or null when nothing matched.
     */
    suspend fun resolveOnYouTube(track: Track): Track? {
        track.youtubeId?.let { return track }
        if (track.isLocal) return null
        if (resolving.putIfAbsent(track.id, true) != null) return null
        try {
            val match = runCatchingCancellable {
                music.findBestMatch(track.artist, track.title, track.durationSec, track.searchQuery)
            }.getOrNull()
            val videoId = match?.youtubeId
            if (videoId == null) {
                tracks.markEnrichFailed(track.id, System.currentTimeMillis())
                return null
            }
            val cover = track.coverUrl?.takeIf { it.startsWith("http") } ?: match.coverUrl
            val duration = if (track.durationSec > 0) track.durationSec else match.durationSec
            tracks.setResolved(track.id, videoId, cover, duration)
            return track.copy(videoId = videoId, coverUrl = cover, durationSec = duration)
        } finally {
            resolving.remove(track.id)
        }
    }

    companion object {
        const val HISTORY_MAX = 60_000

        /** Individual artist names ("A, B & C feat. D" -> [a, b, c, d]). */
        fun splitArtists(artist: String): List<String> =
            artist.split(Regex("""\s*(?:,|&|\bfeat\.?|\bft\.?|\bx\b|\by\b|\band\b)\s*""", RegexOption.IGNORE_CASE))
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
    }
}
