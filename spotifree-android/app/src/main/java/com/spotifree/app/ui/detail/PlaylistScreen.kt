package com.spotifree.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.common.ActionPill
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.theme.LikedGradient

import com.spotifree.app.util.runCatchingCancellable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.PlaylistRoute
import com.spotifree.app.ui.common.ConfirmDialog
import com.spotifree.app.ui.common.EmptyState
import com.spotifree.app.ui.common.MenuItem
import com.spotifree.app.ui.common.NameDialog
import com.spotifree.app.ui.common.TrackRow
import com.spotifree.app.ui.common.UiMessage
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Sort { DEFAULT, TITLE, ARTIST }

@Composable
fun PlaylistScreen(id: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val player by graph.player.state.collectAsStateWithLifecycle()
    val liked by graph.library.likedIds.collectAsStateWithLifecycle()
    val done by graph.downloads.doneIds.collectAsStateWithLifecycle()

    val isLiked = id == Playlist.LIKED_ID
    val isDownloads = id == PlaylistRoute.DOWNLOADS
    val isDevice = id == PlaylistRoute.DEVICE
    val isUser = !isLiked && !isDownloads && !isDevice

    val tracks by remember(id) {
        when {
            isLiked -> graph.library.observeLiked()
            isDownloads -> graph.downloads.observeDownloadedTracks()
            isDevice -> graph.localMusic.observe()
            else -> graph.library.observePlaylistTracks(id)
        }
    }.collectAsState(initial = null)
    val playlist by remember(id) { if (isUser) graph.library.observePlaylist(id) else flowOf(null) }.collectAsState(initial = null)
    val totalBytes by remember { if (isDownloads) graph.downloads.observeTotalBytes().map { it ?: 0L } else flowOf(0L) }.collectAsState(initial = 0L)

    var query by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(Sort.DEFAULT) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    // Device music: permission + scan
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(isDevice, hasPermission) {
        if (isDevice && hasPermission) runCatchingCancellable { graph.localMusic.scan() }
    }

    val title = when {
        isLiked -> t("likedSongs")
        isDownloads -> t("downloadsTitle")
        isDevice -> t("onThisDevice")
        else -> playlist?.title.orEmpty()
    }
    val all = tracks.orEmpty()
    val shown = remember(all, query, sort) {
        val f = all.filter { matches(query, it.title, it.artist, it.album) }
        when (sort) {
            Sort.DEFAULT -> f
            Sort.TITLE -> f.sortedBy { it.title.lowercase() }
            Sort.ARTIST -> f.sortedBy { it.artist.lowercase() }
        }
    }
    val totalSec = all.sumOf { it.durationSec }
    val subtitle = buildString {
        append(strings.songs(all.size))
        if (totalSec > 0) append(" · ").append(
            if (totalSec >= 3600) strings.f("durationHM", totalSec / 3600, (totalSec % 3600) / 60) else strings.f("minutesN", totalSec / 60)
        )
        if (isDownloads && totalBytes > 0) append(" · ").append("%.1f MB".format(totalBytes / 1048576.0))
    }
    // Imported playlists start without a cover: keep the first one found so the Library shows it too
    val firstCover = all.firstNotNullOfOrNull { it.artworkUrl }
    LaunchedEffect(isUser, playlist?.coverUrl, firstCover) {
        if (isUser && playlist != null && playlist?.coverUrl == null && firstCover != null) graph.db.playlists().setCover(id, firstCover)
    }
    val allLiked = all.isNotEmpty() && all.all { it.id in liked }
    val allDownloaded = all.isNotEmpty() && all.filter { !it.isLocal }.all { it.id in done }

    // Previous app: "Recommended" songs under your lists, one tap to add them
    var suggestions by remember(id) { mutableStateOf<List<Track>>(emptyList()) }
    var reloadSuggestions by remember(id) { mutableStateOf(0) }
    val hasSeed = all.any { it.youtubeId != null }
    LaunchedEffect(id, hasSeed, reloadSuggestions) {
        val seedId = all.filter { it.youtubeId != null }.randomOrNull()?.youtubeId
        if ((isUser || isLiked) && seedId != null && !graph.settings.current.offlineMode) {
            val inList = all.map { it.id }.toSet()
            suggestions = runCatchingCancellable { graph.music.radio(seedId, 30) }.getOrDefault(emptyList())
                .filter { it.id !in inList && !graph.library.isBlocked(it) }.take(10)
        }
    }

    val coverUrl = if (isUser) playlist?.coverUrl ?: all.firstNotNullOfOrNull { it.artworkUrl } else null
    val gradient = when {
        isLiked -> LikedGradient
        isDownloads -> listOf(c.accent, Color(0xFF0D3B2E))
        isDevice -> listOf(Color(0xFF535353), Color(0xFF181818))
        else -> null
    }
    val headerColor = rememberHeaderColor(coverUrl, gradient)
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DetailHeader(
                    title = title, subtitle = subtitle,
                    coverUrl = coverUrl,
                    color = headerColor,
                    label = if (isUser) t("playlist") else null,
                    gradient = gradient,
                    icon = when {
                        isLiked -> Icons.Default.Favorite
                        isDownloads -> Icons.Default.DownloadDone
                        isDevice -> Icons.Default.PhoneAndroid
                        else -> Icons.AutoMirrored.Filled.QueueMusic
                    },
                )
                PlayRow(onPlay = { actions.play(shown, 0) }, onShuffle = { actions.play(shown, 0, shuffle = true) }, enabled = shown.isNotEmpty()) {
                    if (!isDownloads && !isDevice) {
                        IconButton(onClick = { if (!allDownloaded) actions.downloadAll(all) }) {
                            Icon(if (allDownloaded) Icons.Default.DownloadDone else Icons.Default.Download, t("downloadAll"), tint = if (allDownloaded) c.accent else c.textSecondary)
                        }
                    }
                    if (isUser) {
                        IconButton(onClick = { scope.launch { graph.library.toggleLikeAll(id) } }) {
                            Icon(if (allLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, t("likeAll"), tint = if (allLiked) c.accent else c.textSecondary)
                        }
                    }
                }
                if (showFilter) FilterField(query) { query = it }
            }
            if (isDevice && !hasPermission) item {
                EmptyState(Icons.Default.PhoneAndroid, t("devicePermissionTitle"), t("devicePermissionSub"), action = {
                    ActionPill(t("allow")) { permLauncher.launch(permission) }
                })
            } else if (tracks != null && all.isEmpty()) item {
                EmptyState(
                    Icons.Default.MusicOff,
                    when {
                        isDownloads -> t("emptyDownloadedTitle")
                        isLiked -> t("emptyLikedTitle")
                        isDevice -> t("emptyDeviceTitle")
                        else -> t("playlistEmpty")
                    },
                    when {
                        isDownloads -> t("emptyDownloadedSub")
                        isLiked -> t("emptyLikedSub")
                        isDevice -> null
                        else -> t("playlistEmptySub")
                    },
                    action = if (isLiked || isUser) ({
                        ActionPill(t("goToSearch"), Icons.Default.Search) { actions.nav.navigate(com.spotifree.app.ui.SearchRoute) }
                    }) else null,
                )
            }
            itemsIndexed(shown) { i, tr ->
                TrackRow(
                    tr,
                    onClick = { actions.play(shown, i) },
                    isCurrent = player.current?.id == tr.id,
                    onMore = { actions.showMenu(tr, MenuContext(playlistId = if (isLiked) null else id)) },
                )
            }
            if (suggestions.isNotEmpty() && query.isBlank()) {
                item {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White.copy(alpha = 0.08f))
                    SectionTitle(t("recommendedSongs"), icon = Icons.Default.AutoAwesome, action = {
                        IconButton(onClick = { reloadSuggestions++ }) { Icon(Icons.Default.Refresh, t("refresh"), tint = c.textSecondary) }
                    })
                }
                items(suggestions, key = { "s-" + it.id }) { tr ->
                    TrackRow(tr, onClick = { actions.play(suggestions, suggestions.indexOf(tr)) }, isCurrent = player.current?.id == tr.id,
                        onLongClick = { actions.showMenu(tr, MenuContext()) },
                        trailing = {
                            IconButton(onClick = {
                                if (isLiked) actions.toggleLike(tr) else actions.addToPlaylist(id, title, listOf(tr))
                                suggestions = suggestions - tr
                            }) {
                                Icon(Icons.Default.AddCircleOutline, t("addToPlaylist"), tint = c.textSecondary)
                            }
                        })
                }
            }
        }
        CollapsingTopBar(title, headerColor, listState, onBack) {
            IconButton(onClick = { showFilter = !showFilter; if (!showFilter) query = "" }) { Icon(Icons.Default.Search, null) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
        }
    }

    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
            Column(Modifier.navigationBarsPadding()) {
                MenuItem(Icons.AutoMirrored.Filled.Sort, when (sort) {
                    Sort.DEFAULT -> t("sortByTitle"); Sort.TITLE -> t("sortByArtist"); Sort.ARTIST -> t("sortDefault")
                }) {
                    sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]; menu = false
                }
                if (all.isNotEmpty()) {
                    MenuItem(Icons.AutoMirrored.Filled.QueueMusic, t("addAllToQueue")) { graph.player.addAllToQueue(shown); menu = false }
                    MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, t("addToPlaylist")) { actions.showAddToPlaylist(shown); menu = false }
                }
                if (!isDownloads && !isDevice && all.isNotEmpty()) MenuItem(Icons.Default.Download, t("downloadAll")) { actions.downloadAll(all); menu = false }
                if (isDevice) MenuItem(Icons.Default.Refresh, t("rescan")) {
                    menu = false
                    scope.launch { val n = runCatchingCancellable { graph.localMusic.scan() }.getOrDefault(0); graph.messages.post(strings.songs(n)) }
                }
                if (isUser) {
                    val ytListId = playlist?.youtubePlaylistId
                    if (ytListId != null) MenuItem(Icons.Default.Refresh, if (refreshing) t("refreshing") else t("refreshFromYouTube")) {
                        menu = false
                        refreshing = true
                        scope.launch {
                            runCatchingCancellable {
                                val list = graph.account.playlistTracks(ytListId)
                                graph.library.replacePlaylistTracks(id, list)
                                graph.messages.post(strings.songs(list.size))
                            }.onFailure { graph.messages.post(strings["importFailed"], isError = true) }
                            refreshing = false
                        }
                    }
                    MenuItem(Icons.Default.Edit, t("rename")) { menu = false; renaming = true }
                    MenuItem(Icons.Default.Delete, t("deletePlaylist"), tint = MaterialTheme3.error()) { menu = false; confirmDelete = true }
                }
                if (isDownloads && all.isNotEmpty()) MenuItem(Icons.Default.Delete, t("clearOfflineData"), tint = MaterialTheme3.error()) {
                    menu = false; confirmDelete = true
                }
            }
        }
    }
    if (renaming) NameDialog(t("rename"), playlist?.title.orEmpty(), onDismiss = { renaming = false }) { name ->
        renaming = false
        scope.launch { graph.library.renamePlaylist(id, name) }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = if (isDownloads) t("clearOfflineData") else t("deletePlaylist"),
            text = if (isDownloads) t("clearOfflineConfirm") else t("deletePlaylistConfirm", title),
            confirm = t("delete"),
            onDismiss = { confirmDelete = false },
        ) {
            scope.launch {
                if (isDownloads) {
                    graph.downloads.clearAll()
                    graph.messages.post(strings["clearedSuccess"])
                } else {
                    val snap = graph.library.deletePlaylist(id)
                    onBack()
                    if (snap != null) graph.messages.post(UiMessage(strings["playlistDeleted"], actionLabel = strings["undo"], action = {
                        graph.scope.launch { graph.library.restorePlaylist(snap) }
                    }))
                }
            }
        }
    }
}

/** Small helper so non-composable lambdas can't capture MaterialTheme by mistake. */
private object MaterialTheme3 {
    @Composable
    fun error(): Color = androidx.compose.material3.MaterialTheme.colorScheme.error
}

