package com.spotifree.app.ui.home

import com.spotifree.app.util.runCatchingCancellable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotifree.app.AppGraph
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.PlaylistRoute
import com.spotifree.app.ui.SettingsRoute
import com.spotifree.app.ui.StatsRoute
import com.spotifree.app.ui.WorkoutRoute
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.EmptyState
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.common.Shelf
import com.spotifree.app.ui.common.ShelfCard
import com.spotifree.app.ui.common.TrackRow
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.Year

data class HomeFeed(
    val recommended: List<Track> = emptyList(),
    val popular: List<Track> = emptyList(),
    val seedTitle: String? = null,
    val loading: Boolean = true,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val _feed = MutableStateFlow(HomeFeed())
    val feed: StateFlow<HomeFeed> = _feed

    init { load() }

    fun load() = viewModelScope.launch {
        if (graph.settings.current.offlineMode) {
            _feed.value = HomeFeed(loading = false)
            return@launch
        }
        _feed.value = _feed.value.copy(loading = true)
        val recent = runCatchingCancellable { graph.library.observeRecent(15).first() }.getOrDefault(emptyList())
        val liked = runCatchingCancellable { graph.library.observeLiked().first().take(50) }.getOrDefault(emptyList())
        val seeds = (recent + liked).filter { it.youtubeId != null }
        val seed = seeds.randomOrNull()
        val known = (recent + liked).map { it.id }.toSet()
        val rec = async {
            seed?.youtubeId?.let { vid -> runCatchingCancellable { graph.music.radio(vid, 30) }.getOrDefault(emptyList()) }
                ?.filter { it.id !in known && !graph.library.isBlocked(it) }?.take(20).orEmpty()
        }
        val pop = async {
            runCatchingCancellable { graph.music.searchSongs(popularQuery(graph.settings.current.language), 20) }.getOrDefault(emptyList())
                .filter { !graph.library.isBlocked(it) }
        }
        _feed.value = HomeFeed(rec.await(), pop.await(), seed?.title, loading = false)
    }

    private fun popularQuery(lang: String): String {
        val y = Year.now().value
        return when (lang) {
            "en" -> "top hits $y"
            "pt" -> "músicas mais tocadas $y"
            "fr" -> "hits du moment $y"
            else -> "éxitos más escuchados $y"
        }
    }
}

@Composable
fun HomeScreen() {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val c = LocalSpotifreeColors.current
    val vm: HomeViewModel = viewModel { HomeViewModel(graph) }
    val feed by vm.feed.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val recent by remember { graph.library.observeRecent(20) }.collectAsState(initial = emptyList())
    val playlists by remember { graph.library.observePlaylists() }.collectAsState(initial = emptyList())
    val followed by remember { graph.library.observeFollowedArtists() }.collectAsState(initial = emptyList())
    val likedCount by remember { graph.db.likes().observeCount() }.collectAsState(initial = 0)
    val player by graph.player.state.collectAsStateWithLifecycle()
    var recognizing by remember { mutableStateOf(false) }

    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> t("greetingMorning")
        in 12..19 -> t("greetingAfternoon")
        else -> t("greetingEvening")
    }

    PullToRefreshBox(isRefreshing = feed.loading && (feed.recommended.isNotEmpty() || feed.popular.isNotEmpty()), onRefresh = { vm.load() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(greeting, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { recognizing = true }) { Icon(Icons.Default.GraphicEq, t("whatsPlaying")) }
                    IconButton(onClick = { actions.nav.navigate(StatsRoute) }) { Icon(Icons.Default.BarChart, t("statsTitle")) }
                    IconButton(onClick = { actions.nav.navigate(SettingsRoute) }) { Icon(Icons.Default.Settings, t("openSettings")) }
                }
                if (settings.offlineMode) {
                    Row(
                        Modifier.padding(16.dp, 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.elevated).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.CloudOff, null, tint = c.accent)
                        Spacer(Modifier.width(12.dp))
                        Text(t("offlineModeOn"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            // Quick access tiles
            item {
                val tiles = buildList {
                    add(Tile(t("likedSongs"), null, Icons.Default.Favorite) { actions.openPlaylist(Playlist.LIKED_ID) })
                    add(Tile(t("downloadsTitle"), null, Icons.Default.Download) { actions.openPlaylist(PlaylistRoute.DOWNLOADS) })
                    playlists.filter { it.id != Playlist.LIKED_ID }.take(4).forEach { p ->
                        add(Tile(p.title, p.coverUrl, Icons.Default.LibraryMusic) { actions.openPlaylist(p.id) })
                    }
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { QuickTile(it, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (settings.enableTGym) item {
                Row(
                    Modifier.padding(16.dp, 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.6f), c.elevated)))
                        .clickable { actions.nav.navigate(WorkoutRoute) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("workoutCompanion"), fontWeight = FontWeight.Bold)
                        Text(t("workoutCompanionDesc"), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
            item {
                Shelf(t("recentlyPlayed"), recent) { tr ->
                    ShelfCard(tr.title, tr.artist, tr.artworkUrl, onClick = { actions.play(recent, recent.indexOf(tr)) },
                        onLongClick = { actions.showMenu(tr, MenuContext()) })
                }
            }
            item {
                Shelf(t("yourArtists"), followed) { a ->
                    ShelfCard(a.name, null, a.imageUrl, onClick = { actions.openArtist(a.url ?: a.name) }, circle = true, width = 120.dp)
                }
            }
            if (feed.recommended.isNotEmpty()) {
                item {
                    SectionTitle(if (feed.seedTitle != null) t("becauseYouListened", feed.seedTitle) else t("recommendedForYou"))
                }
                items(feed.recommended.take(10)) { tr ->
                    TrackRow(tr, onClick = { actions.play(feed.recommended, feed.recommended.indexOf(tr)) },
                        isCurrent = player.current?.id == tr.id, onMore = { actions.showMenu(tr, MenuContext()) })
                }
            }
            if (feed.popular.isNotEmpty()) item {
                Shelf(t("popularMusic"), feed.popular) { tr ->
                    ShelfCard(tr.title, tr.artist, tr.artworkUrl, onClick = { actions.play(feed.popular, feed.popular.indexOf(tr)) },
                        onLongClick = { actions.showMenu(tr, MenuContext()) })
                }
            }
            if (feed.loading && feed.recommended.isEmpty() && feed.popular.isEmpty()) item {
                com.spotifree.app.ui.common.LoadingBox()
            }
            if (!feed.loading && recent.isEmpty() && likedCount == 0 && feed.popular.isEmpty()) item {
                EmptyState(Icons.Default.MusicNote, t("noTracksHome"), t("noTracksHomeSub"), action = {
                    Button(onClick = { vm.load() }) { Text(t("retry")) }
                })
            }
        }
    }

    if (recognizing) RecognitionSheet(onDismiss = { recognizing = false })
}

private data class Tile(val title: String, val image: String?, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun QuickTile(tile: Tile, modifier: Modifier) {
    val c = LocalSpotifreeColors.current
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(6.dp)).background(c.elevated).clickable(onClick = tile.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.image != null) Artwork(tile.image, size = 56.dp, shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
        else Box(Modifier.size(56.dp).background(Brush.linearGradient(listOf(c.accent, c.accent.copy(alpha = 0.4f)))), contentAlignment = Alignment.Center) {
            Icon(tile.icon, null, tint = Color.White)
        }
        Text(tile.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp))
    }
}
