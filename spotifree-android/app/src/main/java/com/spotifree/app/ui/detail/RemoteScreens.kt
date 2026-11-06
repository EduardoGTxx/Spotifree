package com.spotifree.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.spotifree.app.ui.common.ActionPill
import com.spotifree.app.ui.common.OutlinePill
import com.spotifree.app.ui.common.PlayFab

import com.spotifree.app.util.runCatchingCancellable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotifree.app.AppGraph
import com.spotifree.app.data.model.ArtistPage
import com.spotifree.app.data.model.CollectionPage
import com.spotifree.app.data.remote.YouTubeIds
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.common.EmptyState
import com.spotifree.app.ui.common.LoadingBox
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.common.Shelf
import com.spotifree.app.ui.common.ShelfCard
import com.spotifree.app.ui.common.TrackRow
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

class CollectionViewModel(private val graph: AppGraph, private val url: String) : ViewModel() {
    private val _state = MutableStateFlow<Load<CollectionPage>>(Load.Loading)
    val state: StateFlow<Load<CollectionPage>> = _state

    private var job: kotlinx.coroutines.Job? = null

    init { load() }

    /** A retry cancels the request still running, so only the newest answer is shown. */
    fun load() {
        job?.cancel()
        job = viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        _state.value = Load.Loading
        _state.value = runCatchingCancellable { graph.music.collection(url) }.fold(
            { p -> Load.Ok(p.copy(tracks = p.tracks.filter { !graph.library.isBlocked(it) })) },
            { Load.Error(it.message ?: "error") },
        )
    }
}

@Composable
fun CollectionScreen(url: String, initialTitle: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val vm: CollectionViewModel = viewModel(key = url) { CollectionViewModel(graph, url) }
    val state by vm.state.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    val listId = YouTubeIds.playlistId(url)
    val localId = "yt-pl-$listId"
    val saved by remember(localId) { graph.db.playlists().observe(localId) }.collectAsState(initial = null)
    var query by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }

    val page = (state as? Load.Ok)?.value
    val headerColor = rememberHeaderColor(page?.coverUrl)
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            Load.Loading -> LoadingBox(Modifier.fillMaxSize())
            is Load.Error -> EmptyState(Icons.Default.CloudOff, t("loadFailed"), s.message, Modifier.statusBarsPadding().padding(top = 56.dp),
                action = { ActionPill(t("retry"), Icons.Default.Refresh) { vm.load() } })
            is Load.Ok -> {
                val p = s.value
                val shown = remember(p, query) { p.tracks.filter { matches(query, it.title, it.artist, it.album) } }
                val isAlbum = listId?.startsWith("OLAK5uy_") == true
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        DetailHeader(p.title, listOf(p.subtitle, strings.songs(p.tracks.size)).filter { it.isNotBlank() }.joinToString(" · "), p.coverUrl,
                            color = headerColor, label = if (isAlbum) t("album") else t("playlist"))
                        PlayRow(onPlay = { actions.play(shown) }, onShuffle = { actions.play(shown, 0, true) }, enabled = shown.isNotEmpty()) {
                            IconButton(onClick = {
                                scope.launch {
                                    if (saved != null) {
                                        graph.library.deletePlaylist(localId)
                                        graph.messages.post(strings["removedFromLibrary"])
                                    } else {
                                        graph.library.createPlaylist(p.title, p.tracks, id = localId, youtubePlaylistId = listId, coverUrl = p.coverUrl)
                                        graph.messages.post(strings["savedToLibrary"])
                                    }
                                }
                            }) {
                                Icon(if (saved != null) Icons.Default.LibraryAddCheck else Icons.Default.LibraryAdd, t("saveToLibrary"),
                                    tint = if (saved != null) c.accent else c.textSecondary)
                            }
                            IconButton(onClick = { actions.downloadAll(p.tracks) }) { Icon(Icons.Default.Download, t("downloadAll"), tint = c.textSecondary) }
                            IconButton(onClick = { actions.showAddToPlaylist(p.tracks) }) { Icon(Icons.Default.MoreVert, null, tint = c.textSecondary) }
                        }
                        if (showFilter) FilterField(query) { query = it }
                    }
                    itemsIndexed(shown) { i, tr ->
                        TrackRow(tr, onClick = { actions.play(shown, i) }, isCurrent = player.current?.id == tr.id,
                            index = if (isAlbum) i + 1 else null, showArtwork = !isAlbum,
                            onMore = { actions.showMenu(tr, MenuContext()) })
                    }
                }
            }
        }
        CollapsingTopBar(page?.title ?: initialTitle, headerColor, listState, onBack) {
            IconButton(onClick = { showFilter = !showFilter; if (!showFilter) query = "" }) { Icon(Icons.Default.Search, null) }
        }
    }
}

class ArtistViewModel(private val graph: AppGraph, private val nameOrUrl: String) : ViewModel() {
    private val _state = MutableStateFlow<Load<ArtistPage>>(Load.Loading)
    val state: StateFlow<Load<ArtistPage>> = _state

    private var job: kotlinx.coroutines.Job? = null

    init { load() }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        _state.value = Load.Loading
        _state.value = runCatchingCancellable { graph.music.artist(nameOrUrl) }.fold({ Load.Ok(it) }, { Load.Error(it.message ?: "error") })
    }
}

@Composable
fun ArtistScreen(nameOrUrl: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val vm: ArtistViewModel = viewModel(key = nameOrUrl) { ArtistViewModel(graph, nameOrUrl) }
    val state by vm.state.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    val page = (state as? Load.Ok)?.value
    val headerColor = rememberHeaderColor(page?.imageUrl)
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            Load.Loading -> LoadingBox(Modifier.fillMaxSize())
            is Load.Error -> EmptyState(Icons.Default.CloudOff, t("loadFailed"), s.message, Modifier.statusBarsPadding().padding(top = 56.dp),
                action = { ActionPill(t("retry"), Icons.Default.Refresh) { vm.load() } })
            is Load.Ok -> {
                val a = s.value
                val followed by remember(a.name) { graph.library.observeIsFollowed(a.name) }.collectAsState(initial = false)
                val top = a.topTracks.filter { !graph.library.isBlocked(it) }
                val liked by graph.library.likedIds.collectAsStateWithLifecycle()
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        DetailHeader(
                            a.name,
                            if (a.subscribers > 0) strings.f("subscribers", compact(a.subscribers)) else "",
                            a.imageUrl, color = headerColor, icon = Icons.Default.Person, label = t("artist"), artistLayout = true,
                        )
                        // Previous app: play + "Artist radio" + "Follow" pills
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PlayFab({ actions.play(top) }, size = 52.dp, enabled = top.isNotEmpty())
                            OutlinePill(t("artistRadio"), Icons.Default.Radio, tint = Color.White) { top.firstOrNull()?.let { actions.startRadio(it) } }
                            OutlinePill(if (followed) t("following") else t("follow"), if (followed) Icons.Default.Check else Icons.Default.Add,
                                tint = if (followed) c.accent else Color.White, borderColor = if (followed) c.accent else Color.White.copy(alpha = 0.3f)) {
                                scope.launch {
                                    val now = graph.library.toggleFollow(a.name, a.imageUrl, a.url)
                                    graph.messages.post(if (now) strings.f("followingArtist", a.name) else strings.f("unfollowedArtist", a.name))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { actions.play(top, 0, true) }, enabled = top.isNotEmpty()) {
                                Icon(Icons.Default.Shuffle, t("shuffle"), tint = c.textSecondary)
                            }
                        }
                    }
                    if (top.isNotEmpty()) item { SectionTitle(t("popularSongs")) }
                    itemsIndexed(if (expanded) top else top.take(5)) { i, tr ->
                        TrackRow(tr, onClick = { actions.play(top, i) }, isCurrent = player.current?.id == tr.id, index = i + 1,
                            onMore = { actions.showMenu(tr, MenuContext()) },
                            trailing = {
                                IconButton(onClick = { actions.toggleLike(tr) }) {
                                    val isLiked = tr.id in liked
                                    Icon(if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                                        tint = if (isLiked) c.accent else c.textSecondary, modifier = Modifier.size(22.dp))
                                }
                            })
                    }
                    if (top.size > 5) item {
                        OutlinePill(if (expanded) t("showLess") else t("showMore"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            expanded = !expanded
                        }
                    }
                    item {
                        Shelf(t("albumsAndSingles"), a.albums) { al ->
                            ShelfCard(al.title, al.year ?: al.artist, al.coverUrl, onClick = { actions.openCollection(al.url, al.title) })
                        }
                    }
                    a.description?.takeIf { it.isNotBlank() }?.let { d ->
                        item {
                            SectionTitle(t("about"))
                            Text(d, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(16.dp))
                        }
                    }
                }
            }
        }
        CollapsingTopBar(page?.name ?: if (nameOrUrl.startsWith("http")) "" else nameOrUrl, headerColor, listState, onBack)
    }
}

fun compact(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}
