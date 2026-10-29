package com.spotifree.app.ui.home

import com.spotifree.app.util.runCatchingCancellable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import com.spotifree.app.ui.SearchRoute
import com.spotifree.app.ui.common.ActionPill
import com.spotifree.app.ui.common.GradientIcon
import com.spotifree.app.ui.common.PillRow
import com.spotifree.app.ui.common.SectionLink
import com.spotifree.app.ui.common.TopGlow
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.theme.LikedGradient
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
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
    val workout: List<Track> = emptyList(),
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
        val gym = async {
            if (!graph.settings.current.enableTGym) emptyList()
            else runCatchingCancellable { graph.music.searchSongs("gym workout motivation mix", 20) }.getOrDefault(emptyList())
                .filter { !graph.library.isBlocked(it) }
        }
        _feed.value = HomeFeed(rec.await(), pop.await(), seed?.title, gym.await(), loading = false)
    }

    fun popularQuery(lang: String): String {
        val y = Year.now().value
        return when (lang) {
            "en" -> "top hits $y"
            "pt" -> "músicas mais tocadas $y"
            "fr" -> "hits du moment $y"
            else -> "éxitos más escuchados $y"
        }
    }
}

private enum class HomeFilter { ALL, MUSIC, FOR_YOU, WORKOUT }

/** Genre shortcuts of the previous app: coloured icon tiles that search and play right away. */
private data class Shortcut(val labelKey: String, val query: (Int) -> String, val colors: List<Color>, val icon: ImageVector)

private val SHORTCUTS = listOf(
    Shortcut("shortcutLatin", { y -> "hits latinos $y" }, listOf(Color(0xFFFF8A00), Color(0xFFE52E71)), Icons.Default.Public),
    Shortcut("shortcutPop", { y -> "international pop hits $y" }, listOf(Color(0xFFB06AB3), Color(0xFF4568DC)), Icons.Default.AutoAwesome),
    Shortcut("shortcutRock", { _ -> "rock & metal classics" }, listOf(Color(0xFF606060), Color(0xFF232323)), Icons.Default.Speaker),
    Shortcut("shortcutEdm", { y -> "edm party hits $y" }, listOf(Color(0xFF00C6FF), Color(0xFF0072FF)), Icons.Default.GraphicEq),
    Shortcut("shortcutChill", { _ -> "chill lofi relax" }, listOf(Color(0xFF43C6AC), Color(0xFF191654)), Icons.Default.Spa),
)

@Composable
fun HomeScreen() {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val c = LocalSpotifreeColors.current
    val vm: HomeViewModel = viewModel { HomeViewModel(graph) }
    val feed by vm.feed.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val recent by remember { graph.library.observeRecent(20) }.collectAsState(initial = emptyList())
    val playlists by remember { graph.library.observePlaylists() }.collectAsState(initial = emptyList())
    val followed by remember { graph.library.observeFollowedArtists() }.collectAsState(initial = emptyList())
    val likedCount by remember { graph.db.likes().observeCount() }.collectAsState(initial = 0)
    val doneIds by graph.downloads.doneIds.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    var recognizing by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    if (filter == HomeFilter.WORKOUT && !settings.enableTGym) filter = HomeFilter.ALL

    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> t("greetingMorning")
        in 12..19 -> t("greetingAfternoon")
        else -> t("greetingEvening")
    }
    val year = Year.now().value
    val recTitle = if (feed.seedTitle != null) t("becauseYouListened", feed.seedTitle) else t("recommendedForYou")
    val music = filter == HomeFilter.ALL || filter == HomeFilter.MUSIC
    val forYou = filter == HomeFilter.ALL || filter == HomeFilter.FOR_YOU
    val workout = filter == HomeFilter.ALL || filter == HomeFilter.WORKOUT

    Box(Modifier.fillMaxSize()) {
        TopGlow(c.accent, height = 300.dp)
        PullToRefreshBox(
            isRefreshing = feed.loading && (feed.recommended.isNotEmpty() || feed.popular.isNotEmpty()),
            onRefresh = { vm.load() },
            modifier = Modifier.statusBarsPadding(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(greeting, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { recognizing = true }) { Icon(Icons.Default.GraphicEq, t("whatsPlaying")) }
                        IconButton(onClick = { actions.nav.navigate(StatsRoute) }) { Icon(Icons.Default.BarChart, t("statsTitle")) }
                        IconButton(onClick = { actions.nav.navigate(SettingsRoute) }) { Icon(Icons.Outlined.Settings, t("openSettings")) }
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
                    val chips = buildList {
                        add(HomeFilter.ALL to t("filterAll"))
                        add(HomeFilter.MUSIC to t("homeMusic"))
                        add(HomeFilter.FOR_YOU to t("homeForYou"))
                        if (settings.enableTGym) add(HomeFilter.WORKOUT to t("homeWorkout"))
                    }
                    PillRow(chips, filter, PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) { filter = it }
                }
                // Quick access grid: your lists first, genre shortcuts fill the rest
                if (music) item {
                    val tiles = buildList {
                        add(Tile(t("likedSongs"), null, LikedGradient, Icons.Default.Favorite) { actions.openPlaylist(Playlist.LIKED_ID) })
                        if (doneIds.isNotEmpty()) add(Tile(t("downloadsTitle"), null, listOf(c.accent, Color(0xFF0D3B2E)), Icons.Default.DownloadDone) {
                            actions.openPlaylist(PlaylistRoute.DOWNLOADS)
                        })
                        playlists.filter { it.id != Playlist.LIKED_ID }.take(3).forEach { p ->
                            add(Tile(p.title, p.coverUrl, listOf(c.elevated, c.elevated), Icons.Default.LibraryMusic) { actions.openPlaylist(p.id) })
                        }
                        add(Tile(t("shortcutTopHits"), null, listOf(Color(0xFFF5576C), Color(0xFFF093FB)), Icons.Default.Whatshot) {
                            actions.playQuery(vm.popularQuery(settings.language))
                        })
                        if (settings.enableTGym) add(Tile(t("shortcutGym"), null, listOf(c.accent, Color(0xFF1A1A2E)), Icons.Default.FitnessCenter) {
                            actions.nav.navigate(WorkoutRoute)
                        })
                        SHORTCUTS.forEach { sc -> add(Tile(strings[sc.labelKey], null, sc.colors, sc.icon) { actions.playQuery(sc.query(year)) }) }
                    }.let { all -> all.take(minOf(8, all.size - all.size % 2)) }
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { QuickTile(it, Modifier.weight(1f)) }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (workout && settings.enableTGym) item {
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.7f), c.elevated)))
                            .clickable { actions.nav.navigate(WorkoutRoute) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("workoutCompanion"), fontWeight = FontWeight.ExtraBold)
                            Text(t("workoutCompanionDesc"), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
                if (workout && feed.workout.isNotEmpty()) item {
                    Shelf(t("gymMix"), feed.workout, icon = Icons.Default.FitnessCenter) { tr ->
                        ShelfCard(tr.title, tr.artist, tr.artworkUrl, onClick = { actions.play(feed.workout, feed.workout.indexOf(tr)) },
                            onLongClick = { actions.showMenu(tr, MenuContext()) }, onPlay = { actions.play(feed.workout, feed.workout.indexOf(tr)) })
                    }
                }
                if (music) item {
                    Shelf(t("recentlyPlayed"), recent) { tr ->
                        ShelfCard(tr.title, tr.artist, tr.artworkUrl, onClick = { actions.play(recent, recent.indexOf(tr)) },
                            onLongClick = { actions.showMenu(tr, MenuContext()) })
                    }
                }
                if (music) item {
                    Shelf(t("popularMusic"), feed.popular, action = { SectionLink(t("searchMore")) { actions.nav.navigate(SearchRoute) } }) { tr ->
                        ShelfCard(tr.title, tr.artist, tr.artworkUrl, onClick = { actions.play(feed.popular, feed.popular.indexOf(tr)) },
                            onLongClick = { actions.showMenu(tr, MenuContext()) }, onPlay = { actions.play(feed.popular, feed.popular.indexOf(tr)) })
                    }
                }
                if (feed.recommended.isNotEmpty() && forYou) {
                    if (filter == HomeFilter.FOR_YOU) {
                        // "For you": the full list, like the current app
                        item { SectionTitle(recTitle, icon = Icons.Default.AutoAwesome) }
                        items(feed.recommended) { tr ->
                            TrackRow(tr, onClick = { actions.play(feed.recommended, feed.recommended.indexOf(tr)) },
                                isCurrent = player.current?.id == tr.id, onMore = { actions.showMenu(tr, MenuContext()) })
                        }
                    } else item {
                        Shelf(recTitle, feed.recommended, icon = Icons.Default.AutoAwesome, action = { SectionLink(t("explore")) { filter = HomeFilter.FOR_YOU } }) { tr ->
                            ShelfCard(tr.title, tr.artist, tr.artworkUrl, onClick = { actions.play(feed.recommended, feed.recommended.indexOf(tr)) },
                                onLongClick = { actions.showMenu(tr, MenuContext()) }, onPlay = { actions.play(feed.recommended, feed.recommended.indexOf(tr)) })
                        }
                    }
                }
                if (forYou) item {
                    Shelf(t("yourArtists"), followed) { a ->
                        ShelfCard(a.name, null, a.imageUrl, onClick = { actions.openArtist(a.url ?: a.name) }, circle = true, width = 120.dp)
                    }
                }
                if (feed.loading && feed.recommended.isEmpty() && feed.popular.isEmpty()) item {
                    com.spotifree.app.ui.common.LoadingBox()
                }
                if (!feed.loading && recent.isEmpty() && likedCount == 0 && feed.popular.isEmpty()) item {
                    EmptyState(Icons.Default.MusicNote, t("noTracksHome"), t("noTracksHomeSub"), action = {
                        ActionPill(t("retry"), Icons.Default.Refresh) { vm.load() }
                    })
                }
            }
        }
    }

    if (recognizing) RecognitionSheet(onDismiss = { recognizing = false })
}

private data class Tile(val title: String, val image: String?, val colors: List<Color>, val icon: ImageVector, val onClick: () -> Unit)

/** Spotify's two-column shortcut: cover (or coloured icon) on the left, bold title. */
@Composable
private fun QuickTile(tile: Tile, modifier: Modifier) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = tile.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.image != null) Artwork(tile.image, size = 56.dp, shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
        else GradientIcon(tile.colors, tile.icon, 56.dp, Modifier.clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)))
        Text(tile.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 10.dp))
    }
}
