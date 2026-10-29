package com.spotifree.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spotifree.app.data.model.Track

/**
 * Songs travel through the player as MediaItems whose URI is "spotifree://track/<id>".
 * The real audio URL (download, local file or a YouTube stream) is chosen only when the player
 * opens the item (see TrackResolver), so queues of thousands of songs cost nothing up front.
 */
object MediaItems {
    const val SCHEME = "spotifree"
    private const val EXTRA_ID = "sf_id"
    private const val EXTRA_VIDEO = "sf_vid"
    private const val EXTRA_DURATION = "sf_dur"
    private const val EXTRA_QUERY = "sf_q"
    private const val EXTRA_LOCAL = "sf_local"
    private const val EXTRA_ARTIST_URL = "sf_artist_url"
    private const val EXTRA_COVER = "sf_cover"

    /**
     * Cover saved with a download (file path), set by the app graph. The notification, lock screen
     * and Android Auto then show the cover offline too; the remote URL stays in the extras.
     */
    @Volatile
    var localCover: (trackId: String) -> String? = { null }

    fun uriFor(track: Track): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority("track")
        .appendPath(track.id)
        .apply { track.youtubeId?.let { appendQueryParameter("v", it) } }
        .build()

    fun trackIdOf(uri: Uri): String? = if (uri.scheme == SCHEME) uri.pathSegments.firstOrNull() else null

    fun toMediaItem(track: Track): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_ID, track.id)
            track.youtubeId?.let { putString(EXTRA_VIDEO, it) }
            putInt(EXTRA_DURATION, track.durationSec)
            track.searchQuery?.let { putString(EXTRA_QUERY, it) }
            track.localUri?.let { putString(EXTRA_LOCAL, it) }
            track.artistUrl?.let { putString(EXTRA_ARTIST_URL, it) }
            track.coverUrl?.let { putString(EXTRA_COVER, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .setArtworkUri(localCover(track.id)?.let { Uri.fromFile(java.io.File(it)) } ?: track.artworkUrl?.let { Uri.parse(it) })
            .setDurationMs(if (track.durationSec > 0) track.durationSec * 1000L else null)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriFor(track))
            .setMediaMetadata(metadata)
            .build()
    }

    fun toTrack(item: MediaItem): Track {
        val md = item.mediaMetadata
        val ex = md.extras
        return Track(
            id = ex?.getString(EXTRA_ID) ?: item.mediaId,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            durationSec = ex?.getInt(EXTRA_DURATION) ?: ((md.durationMs ?: 0L) / 1000).toInt(),
            coverUrl = ex?.getString(EXTRA_COVER) ?: md.artworkUri?.toString(),
            videoId = ex?.getString(EXTRA_VIDEO) ?: item.localConfiguration?.uri?.getQueryParameter("v"),
            searchQuery = ex?.getString(EXTRA_QUERY),
            localUri = ex?.getString(EXTRA_LOCAL),
            artistUrl = ex?.getString(EXTRA_ARTIST_URL),
        )
    }

    /** A browsable folder for Android Auto / the system media browser. */
    fun folder(id: String, title: String, artwork: String? = null, mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .setArtworkUri(artwork?.let { Uri.parse(it) })
                    .build()
            )
            .build()
}
