package com.spotifree.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.data.model.Track

@Entity(tableName = "tracks", indices = [Index("videoId")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val coverUrl: String? = null,
    val videoId: String? = null,
    val searchQuery: String? = null,
    val localUri: String? = null,
    val artistUrl: String? = null,
    /** Last automatic YouTube lookup that found nothing (skip it for a while). */
    val enrichFailedAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun toTrack() = Track(
        id = id, title = title, artist = artist, album = album, durationSec = durationSec,
        coverUrl = coverUrl, videoId = videoId, searchQuery = searchQuery, localUri = localUri,
        artistUrl = artistUrl,
    )

    companion object {
        fun from(t: Track, addedAt: Long = System.currentTimeMillis()) = TrackEntity(
            id = t.id, title = t.title, artist = t.artist, album = t.album, durationSec = t.durationSec,
            coverUrl = t.coverUrl, videoId = t.videoId, searchQuery = t.searchQuery, localUri = t.localUri,
            artistUrl = t.artistUrl, addedAt = addedAt,
        )
    }
}

@Entity(tableName = "likes", indices = [Index("likedAt")])
data class LikeEntity(
    @PrimaryKey val trackId: String,
    val likedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val coverUrl: String? = null,
    val youtubePlaylistId: String? = null,
    val isSpotifyImport: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val sortIndex: Long = System.currentTimeMillis(),
) {
    fun toPlaylist(count: Int) = Playlist(
        id = id, title = title, description = description, coverUrl = coverUrl, trackCount = count,
        youtubePlaylistId = youtubePlaylistId, isSpotifyImport = isSpotifyImport,
    )
}

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("trackId"), Index(value = ["playlistId", "position"])],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val position: Long,
)

@Entity(tableName = "followed_artists")
data class FollowedArtistEntity(
    @PrimaryKey val key: String,
    val name: String,
    val imageUrl: String? = null,
    val url: String? = null,
    val followedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "blocked_artists")
data class BlockedArtistEntity(
    @PrimaryKey val key: String,
    val name: String,
)

@Entity(tableName = "blocked_tracks")
data class BlockedTrackEntity(
    @PrimaryKey val trackId: String,
    val title: String = "",
    val artist: String = "",
)

object DownloadState {
    const val QUEUED = 0
    const val RUNNING = 1
    const val DONE = 2
    const val FAILED = 3
}

@Entity(tableName = "downloads", indices = [Index("state")])
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val state: Int = DownloadState.QUEUED,
    val progress: Float = 0f,
    val filePath: String? = null,
    val coverPath: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val savedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    /** false = kept by the automatic "save while listening" cache (evicted first). */
    val isManual: Boolean = true,
    val error: String? = null,
)

@Entity(tableName = "history", indices = [Index("playedAt"), Index("trackId"), Index("artist")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val playedAt: Long,
    val secondsPlayed: Int,
    /** "app" or "spotify" (imported streaming history). */
    val source: String = "app",
)

@Entity(tableName = "saved_queue")
data class SavedQueueEntity(
    @PrimaryKey val position: Int,
    val trackId: String,
)

/** Which audio format a video was cached with, so the stream cache can be read offline. */
@Entity(tableName = "stream_formats")
data class StreamFormatEntity(
    @PrimaryKey val videoId: String,
    val itag: Int,
    val mimeType: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val savedAt: Long = System.currentTimeMillis(),
)

/** Aggregates used by stats / Rewind. */
data class TrackPlayCount(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val plays: Int,
    val seconds: Long,
)

data class ArtistPlayCount(
    val artist: String,
    val plays: Int,
    val seconds: Long,
)

data class DayCount(
    val day: String,
    val plays: Int,
    val seconds: Long,
)

data class HourCount(
    val hour: Int,
    val plays: Int,
)
