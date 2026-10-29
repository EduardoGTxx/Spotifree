package com.spotifree.app.ui

import com.spotifree.app.util.runCatchingCancellable

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import com.spotifree.app.AppGraph
import com.spotifree.app.data.library.AddResult
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.common.UiMessage
import com.spotifree.app.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object SearchRoute
@Serializable object LibraryRoute
@Serializable object WorkoutRoute
@Serializable object SettingsRoute
@Serializable object StatsRoute
@Serializable object RewindRoute
@Serializable object SpotifyImportRoute
@Serializable object YouTubeSignInRoute
@Serializable object BlockedRoute
@Serializable object DownloadsManagerRoute

/** A playlist kept in the app: an id from Room, or [com.spotifree.app.data.model.Playlist.LIKED_ID], [DOWNLOADS], [DEVICE]. */
@Serializable data class PlaylistRoute(val id: String) {
    companion object {
        const val DOWNLOADS = "downloads"
        const val DEVICE = "device"
    }
}

/** A YouTube playlist or album, read online. */
@Serializable data class CollectionRoute(val url: String, val title: String = "")

@Serializable data class ArtistRoute(val nameOrUrl: String)

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

/** Where a track menu was opened from (adds "remove from this playlist" / queue actions). */
data class MenuContext(val playlistId: String? = null, val queueIndex: Int? = null)

/** Actions shared by every screen: playing, opening pages, menus and sheets. */
class AppActions(
    val graph: AppGraph,
    val scope: CoroutineScope,
    val nav: NavController,
    private val strings: () -> Strings,
    val showMenu: (Track, MenuContext) -> Unit,
    val showAddToPlaylist: (List<Track>) -> Unit,
    val openPlayer: () -> Unit,
) {
    private val s get() = strings()

    /** Plays [tracks] from [index], skipping blocked songs/artists. */
    fun play(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val start = tracks.getOrNull(index)
        val allowed = tracks.filter { it == start || !graph.library.isBlocked(it) }
        val newIndex = start?.let { allowed.indexOf(it) }?.coerceAtLeast(0) ?: 0
        val offline = graph.settings.current.offlineMode
        if (offline) {
            val done = graph.downloads.doneIds.value
            val playable = allowed.filter { it.isLocal || it.id in done }
            if (playable.isEmpty()) {
                graph.messages.post(s["offlineNothingPlayable"], isError = true)
                return
            }
            val i = start?.let { playable.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            graph.player.playTracks(playable, i, shuffle)
            return
        }
        graph.player.playTracks(allowed, newIndex, shuffle)
    }

    fun playNext(t: Track) {
        graph.player.playNext(t)
        graph.messages.post(s["addedPlayNext"])
    }

    fun addToQueue(t: Track) {
        graph.player.addToQueue(t)
        graph.messages.post(s["addedToQueue"])
    }

    fun startRadio(t: Track) {
        graph.player.startRadio(t)
        graph.messages.post(s["radioStarted"])
    }

    fun toggleLike(t: Track) = scope.launch {
        val liked = graph.library.toggleLike(t)
        if (liked) graph.downloads.onLiked(t)
        graph.messages.post(if (liked) s["addedToLiked"] else s["removedFromLiked"])
    }

    fun download(t: Track) = scope.launch {
        graph.downloads.enqueue(t)
        graph.messages.post(s.f("downloadQueued", t.title))
    }

    fun downloadAll(list: List<Track>) = scope.launch {
        graph.downloads.enqueueAll(list.filter { !it.isLocal })
        graph.messages.post(s.f("downloadQueuedMany", list.size))
    }

    fun removeDownload(t: Track) = scope.launch {
        graph.downloads.remove(t.id)
        graph.messages.post(s["downloadRemoved"])
    }

    fun addToPlaylist(playlistId: String, title: String, tracks: List<Track>) = scope.launch {
        var added = 0
        tracks.forEach { if (graph.library.addToPlaylist(playlistId, it) == AddResult.ADDED) added++ }
        graph.messages.post(
            when {
                tracks.size == 1 && added == 0 -> s.f("alreadyInPlaylist", title)
                else -> s.f("addedToPlaylist", title)
            }
        )
    }

    fun removeFromPlaylist(playlistId: String, t: Track) = scope.launch {
        val idx = graph.library.removeFromPlaylist(playlistId, t.id) ?: return@launch
        graph.messages.post(UiMessage(s["removedFromPlaylist"], actionLabel = s["undo"], action = {
            scope.launch { graph.library.restoreToPlaylist(playlistId, t.id, idx) }
        }))
    }

    fun openArtist(t: Track) {
        val url = t.artistUrl
        val name = com.spotifree.app.data.library.LibraryRepository.splitArtists(t.artist).firstOrNull() ?: t.artist
        openArtist(url ?: name)
    }

    fun openArtist(nameOrUrl: String) = nav.navigate(ArtistRoute(nameOrUrl)) { launchSingleTop = true }
    fun openCollection(url: String, title: String = "") = nav.navigate(CollectionRoute(url, title)) { launchSingleTop = true }
    fun openPlaylist(id: String) = nav.navigate(PlaylistRoute(id)) { launchSingleTop = true }

    fun openAlbum(t: Track) = scope.launch {
        if (t.album.isBlank()) return@launch
        val res = runCatchingCancellable { graph.music.search("${t.album} ${t.artist}", com.spotifree.app.data.model.SearchFilter.ALBUMS) }.getOrNull()
        val album = res?.albums?.firstOrNull()
        if (album != null) openCollection(album.url, album.title) else graph.messages.post(s["albumNotFound"], isError = true)
    }

    fun share(context: android.content.Context, t: Track) {
        val url = t.youtubeId?.let { "https://music.youtube.com/watch?v=$it" }
        val text = listOfNotNull("${t.title} — ${t.artist}", url).joinToString("\n")
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
        context.startActivity(android.content.Intent.createChooser(intent, s["share"]).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun blockTrack(t: Track) = scope.launch {
        graph.library.blockTrack(t)
        graph.messages.post(UiMessage(s["trackHidden"], actionLabel = s["undo"], action = { scope.launch { graph.library.unblockTrack(t.id) } }))
    }

    fun blockArtist(t: Track) = scope.launch {
        val key = graph.library.blockArtist(t.artist) ?: return@launch
        graph.messages.post(UiMessage(s.f("artistHidden", key), actionLabel = s["undo"], action = { scope.launch { graph.library.unblockArtist(key) } }))
    }
}
