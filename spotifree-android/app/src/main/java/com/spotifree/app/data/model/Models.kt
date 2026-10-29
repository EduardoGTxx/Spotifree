package com.spotifree.app.data.model

import kotlinx.serialization.Serializable

/**
 * A song as the app knows it. Ids keep the format of the previous (Capacitor) app so a migrated
 * library, its playlists and its history keep pointing at the same songs:
 *  - "yt-<videoId>"        songs found on YouTube / YouTube Music
 *  - "spotify-<id>"        songs imported from Spotify (resolved on YouTube when played)
 *  - "local-<mediaStoreId>" audio files on the phone
 */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val coverUrl: String? = null,
    val videoId: String? = null,
    val searchQuery: String? = null,
    val localUri: String? = null,
    val artistUrl: String? = null,
) {
    val isLocal: Boolean get() = localUri != null || id.startsWith("local-")

    /** Video id usable for streaming, from the field or from the id itself. */
    val youtubeId: String?
        get() = videoId ?: when {
            id.startsWith("yt-rec-") -> id.removePrefix("yt-rec-")
            id.startsWith("yt-") -> id.removePrefix("yt-")
            else -> null
        }

    /** Square cover that always exists for YouTube songs (the stored URL may be missing). */
    val artworkUrl: String?
        get() = coverUrl?.takeIf { it.startsWith("http") || it.startsWith("content:") || it.startsWith("file:") }
            ?: youtubeId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    companion object {
        fun idForVideo(videoId: String) = "yt-$videoId"
    }
}

data class Playlist(
    val id: String,
    val title: String,
    val description: String = "",
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val youtubePlaylistId: String? = null,
    val isSpotifyImport: Boolean = false,
) {
    val isLiked: Boolean get() = id == LIKED_ID

    companion object {
        const val LIKED_ID = "liked-songs"
    }
}

data class ArtistRef(
    val name: String,
    val url: String? = null,
    val imageUrl: String? = null,
    val subtitle: String? = null,
)

data class AlbumRef(
    val title: String,
    val artist: String,
    val url: String,
    val coverUrl: String? = null,
    val year: String? = null,
)

data class PlaylistRef(
    val title: String,
    val owner: String,
    val url: String,
    val coverUrl: String? = null,
    val count: Long = -1,
)

data class ArtistPage(
    val name: String,
    val imageUrl: String?,
    val bannerUrl: String?,
    val subscribers: Long,
    val description: String?,
    val topTracks: List<Track>,
    val albums: List<AlbumRef>,
    val url: String,
)

data class CollectionPage(
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
    val tracks: List<Track>,
    val url: String,
)

enum class SearchFilter { SONGS, VIDEOS, ALBUMS, ARTISTS, PLAYLISTS }

data class SearchResults(
    val songs: List<Track> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
)

data class LyricLine(
    val timeSec: Double,
    val text: String,
    val words: List<LyricWord>? = null,
    val isInstrumental: Boolean = false,
)

data class LyricWord(val timeSec: Double, val text: String)

data class Lyrics(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val source: String? = null,
)
