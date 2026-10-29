package com.spotifree.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.data.db.DownloadState
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.PlaylistRoute
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.launch

@Composable
fun MenuItem(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint ?: LocalSpotifreeColors.current.textSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun TrackMenuSheet(track: Track, ctx: MenuContext, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liked by graph.library.likedIds.collectAsStateWithLifecycle()
    val downloads by graph.downloads.all.collectAsStateWithLifecycle()
    val d = downloads[track.id]
    val isLiked = track.id in liked
    val strings = com.spotifree.app.ui.i18n.LocalStrings.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = LocalSpotifreeColors.current
    fun act(block: () -> Unit) {
        block()
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artworkUrl, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.surface)
            MenuItem(if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (isLiked) t("removeFromLiked") else t("addToLiked"),
                tint = if (isLiked) c.accent else null) { act { actions.toggleLike(track) } }
            MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, t("addToPlaylist")) { act { actions.showAddToPlaylist(listOf(track)) } }
            if (ctx.queueIndex == null) {
                MenuItem(Icons.Default.PlaylistPlay, t("playNext")) { act { actions.playNext(track) } }
                MenuItem(Icons.AutoMirrored.Filled.QueueMusic, t("addToQueueAction")) { act { actions.addToQueue(track) } }
            } else {
                MenuItem(Icons.Default.RemoveCircleOutline, t("removeFromQueue")) { act { graph.player.removeQueueItem(ctx.queueIndex) } }
            }
            if (!track.isLocal) MenuItem(Icons.Default.Radio, t("startRadio")) { act { actions.startRadio(track) } }
            if (!track.isLocal) {
                when (d?.state) {
                    DownloadState.DONE -> {
                        MenuItem(Icons.Default.FileDownloadOff, t("removeDownload")) { act { actions.removeDownload(track) } }
                        MenuItem(Icons.Default.SaveAlt, t("saveToMusicFolder")) {
                            act {
                                scope.launch {
                                    val ok = graph.downloads.exportToMusic(track)
                                    graph.messages.post(if (ok) strings["savedToMusicFolder"] else strings["saveToMusicFailed"], isError = !ok)
                                }
                            }
                        }
                    }
                    DownloadState.RUNNING, DownloadState.QUEUED -> MenuItem(Icons.Default.FileDownloadOff, t("cancelDownload")) {
                        act { scope.launch { graph.downloads.cancel(track.id) } }
                    }
                    else -> MenuItem(Icons.Default.Download, t("download")) { act { actions.download(track) } }
                }
            }
            if (!track.isLocal) MenuItem(Icons.Default.Person, t("goToArtist")) { act { actions.openArtist(track) } }
            if (track.album.isNotBlank() && !track.isLocal) MenuItem(Icons.Default.Album, t("goToAlbum")) { act { actions.openAlbum(track) } }
            val pid = ctx.playlistId
            if (pid != null && pid != PlaylistRoute.DOWNLOADS && pid != PlaylistRoute.DEVICE) {
                MenuItem(Icons.Default.Delete, t("removeFromThisPlaylist")) { act { actions.removeFromPlaylist(pid, track) } }
            }
            val jam by graph.jam.state.collectAsStateWithLifecycle()
            if (jam.status == "guest") MenuItem(Icons.Default.Groups, t("jamSuggest")) {
                act {
                    val me = graph.account.account.value?.name?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
                    if (graph.jam.suggest(track, me)) graph.messages.post(strings["jamSuggestionSent"])
                }
            }
            MenuItem(Icons.Default.Share, t("share")) { act { actions.share(context, track) } }
            MenuItem(Icons.Default.Block, t("hideSong")) { act { actions.blockTrack(track) } }
            MenuItem(Icons.Default.PersonOff, t("hideArtist")) { act { actions.blockArtist(track) } }
        }
    }
}

@Composable
fun AddToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val playlists by graph.library.observePlaylists().collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    val strings = com.spotifree.app.ui.i18n.LocalStrings.current
    val c = LocalSpotifreeColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
        Text(t("addToPlaylist"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        MenuItem(Icons.Default.Add, t("newPlaylist"), tint = c.accent) { creating = true }
        LazyColumn(Modifier.heightIn(max = 480.dp).navigationBarsPadding()) {
            items(playlists.filter { it.id != Playlist.LIKED_ID }, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        actions.addToPlaylist(p.id, p.title, tracks)
                        onDismiss()
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(p.coverUrl, size = 48.dp, placeholder = Icons.Default.LibraryMusic)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(LocalStringsSongs(p.trackCount), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog(title = t("newPlaylist"), initial = "", onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch {
                graph.library.createPlaylist(name, tracks)
                graph.messages.post(strings.f("playlistCreated", name))
            }
            onDismiss()
        }
    }
}

@Composable
fun LocalStringsSongs(n: Int): String = com.spotifree.app.ui.i18n.LocalStrings.current.songs(n)

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text(t("playlistNameHint")) })
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(t("save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String?, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
