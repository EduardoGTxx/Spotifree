package com.spotifree.app.playback

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spotifree.app.AppGraph
import com.spotifree.app.R
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.data.model.Track
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Library tree shown by Android Auto and other media browsers:
 *   root -> Liked songs | Downloads | Playlists (-> each playlist) | Recently played
 * Songs inside a folder carry "ctx|<folder>|<trackId>" as id so that tapping one queues the
 * whole folder starting at that song.
 */
class BrowseTree(private val context: Context, private val graph: AppGraph) {
    private val searchResults = ConcurrentHashMap<String, List<Track>>()

    fun root(): MediaItem = MediaItems.folder(ROOT, context.getString(R.string.app_name))

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            MediaItems.folder(LIKED, context.getString(R.string.liked_songs), mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            MediaItems.folder(DOWNLOADS, context.getString(R.string.downloads), mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            MediaItems.folder(PLAYLISTS, context.getString(R.string.playlists)),
            MediaItems.folder(RECENT, context.getString(R.string.recently_played), mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
        )
        parentId == PLAYLISTS -> graph.library.allPlaylists().map {
            MediaItems.folder("$PLAYLIST_PREFIX${it.id}", it.title, it.coverUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        }
        else -> folderTracks(parentId).take(MAX_CHILDREN).map { contextItem(parentId, it) }
    }

    private suspend fun folderTracks(folderId: String): List<Track> = when {
        folderId == LIKED -> graph.library.playlistTracks(Playlist.LIKED_ID)
        folderId == DOWNLOADS -> graph.db.downloads().observeDoneTracks().first().map { it.toTrack() }
        folderId == RECENT -> graph.library.observeRecent(50).first()
        folderId.startsWith(PLAYLIST_PREFIX) -> graph.library.playlistTracks(folderId.removePrefix(PLAYLIST_PREFIX))
        folderId.startsWith(SEARCH_PREFIX) -> searchResults[folderId.removePrefix(SEARCH_PREFIX)].orEmpty()
        else -> emptyList()
    }

    private fun contextItem(folderId: String, track: Track): MediaItem {
        val base = MediaItems.toMediaItem(track)
        return base.buildUpon().setMediaId("$CTX|$folderId|${track.id}").build()
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == ROOT) return root()
        val trackId = if (mediaId.startsWith("$CTX|")) mediaId.substringAfterLast('|') else mediaId
        return graph.library.track(trackId)?.let { MediaItems.toMediaItem(it) }
    }

    /** A single "song inside a folder" request becomes that whole folder. */
    suspend fun expandContext(items: List<MediaItem>): Pair<List<Track>, Int>? {
        val single = items.singleOrNull() ?: return null
        if (!single.mediaId.startsWith("$CTX|")) return null
        val parts = single.mediaId.split('|')
        if (parts.size < 3) return null
        val folder = parts[1]
        val trackId = parts.drop(2).joinToString("|")
        val tracks = folderTracks(folder).take(MAX_QUEUE)
        if (tracks.isEmpty()) return null
        return tracks to tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
    }

    /** Rebuilds playable items from what a controller sent (ids, metadata extras). */
    suspend fun expandToTracks(items: List<MediaItem>): List<Track> = items.mapNotNull { item ->
        val id = if (item.mediaId.startsWith("$CTX|")) item.mediaId.substringAfterLast('|') else item.mediaId
        val fromItem = MediaItems.toTrack(item)
        when {
            fromItem.title.isNotBlank() -> fromItem.copy(id = id)
            else -> graph.library.track(id)
        }
    }

    suspend fun search(query: String): Int {
        val local = graph.db.tracks().searchLocal(query, 20).map { it.toTrack() }
        val remote = if (graph.settings.current.offlineMode) emptyList()
        else runCatchingCancellable { graph.music.searchSongs(query, 20) }.getOrDefault(emptyList())
        val all = (local + remote).distinctBy { it.id }
        searchResults[query] = all
        return all.size
    }

    fun searchResults(query: String): List<MediaItem> =
        searchResults[query].orEmpty().map { contextItem("$SEARCH_PREFIX$query", it) }

    companion object {
        const val ROOT = "root"
        const val LIKED = "liked"
        const val DOWNLOADS = "downloads"
        const val PLAYLISTS = "playlists"
        const val RECENT = "recent"
        const val PLAYLIST_PREFIX = "pl:"
        const val SEARCH_PREFIX = "search:"
        const val CTX = "ctx"
        private const val MAX_CHILDREN = 300
        private const val MAX_QUEUE = 1000
    }
}
