package com.spotifree.app.ui.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Piano
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import com.spotifree.app.ui.common.CategoryCard
import com.spotifree.app.ui.common.OutlinePill
import com.spotifree.app.ui.common.PillRow

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotifree.app.AppGraph
import com.spotifree.app.data.db.TrackEntity
import com.spotifree.app.data.model.SearchFilter
import com.spotifree.app.data.model.SearchResults
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.EmptyState
import com.spotifree.app.ui.common.LoadingBox
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.common.ShelfCard
import com.spotifree.app.ui.common.TrackRow
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val submitted: String = "",
    val filter: SearchFilter? = null,
    val loading: Boolean = false,
    val results: SearchResults? = null,
    val local: List<Track> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val error: Boolean = false,
    val history: List<String> = emptyList(),
)

class SearchViewModel(private val graph: AppGraph) : ViewModel() {
    private val prefs = graph.context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(SearchUi(history = loadHistory()))
    val ui: StateFlow<SearchUi> = _ui
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    private fun loadHistory() = prefs.getString("items", "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun saveHistory(list: List<String>) {
        prefs.edit().putString("items", list.joinToString("\n")).apply()
        _ui.value = _ui.value.copy(history = list)
    }

    fun onQuery(q: String) {
        _ui.value = _ui.value.copy(query = q)
        suggestJob?.cancel()
        if (q.isBlank()) {
            _ui.value = _ui.value.copy(suggestions = emptyList(), results = null, submitted = "", local = emptyList())
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            val local = runCatchingCancellable { graph.db.tracks().searchLocal(q.trim(), 8).map(TrackEntity::toTrack) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(local = local)
            if (graph.settings.current.offlineMode) return@launch
            val s = runCatchingCancellable { graph.music.suggestions(q) }.getOrDefault(emptyList())
            if (_ui.value.query == q) _ui.value = _ui.value.copy(suggestions = s.take(8))
        }
    }

    fun submit(q: String = _ui.value.query, filter: SearchFilter? = _ui.value.filter) {
        val query = q.trim()
        if (query.isEmpty()) return
        suggestJob?.cancel()
        searchJob?.cancel()
        saveHistory((listOf(query) + _ui.value.history.filter { !it.equals(query, true) }).take(15))
        _ui.value = _ui.value.copy(query = query, submitted = query, filter = filter, loading = true, error = false, suggestions = emptyList())
        searchJob = viewModelScope.launch {
            val local = runCatchingCancellable { graph.db.tracks().searchLocal(query, 20).map(TrackEntity::toTrack) }.getOrDefault(emptyList())
            if (graph.settings.current.offlineMode) {
                _ui.value = _ui.value.copy(loading = false, local = local, results = SearchResults())
                return@launch
            }
            val res = runCatchingCancellable { graph.music.search(query, filter) }
            _ui.value = _ui.value.copy(
                loading = false,
                local = local,
                results = res.getOrNull()?.let { r -> r.copy(songs = r.songs.filter { !graph.library.isBlocked(it) }) },
                error = res.isFailure,
            )
        }
    }

    fun setFilter(f: SearchFilter?) {
        if (_ui.value.submitted.isNotBlank()) submit(_ui.value.submitted, f) else _ui.value = _ui.value.copy(filter = f)
    }

    fun removeHistory(q: String) = saveHistory(_ui.value.history - q)
    fun clearHistory() = saveHistory(emptyList())
    fun clear() = onQuery("")
}

private val GENRES = listOf(
    "Pop" to 0xFFE13300, "Reggaeton" to 0xFF1E3264, "Rock" to 0xFF8D67AB, "Hip Hop" to 0xFFBA5D07,
    "Electrónica" to 0xFF0D73EC, "Latina" to 0xFFE1118C, "Indie" to 0xFF608108, "R&B" to 0xFFDC148C,
    "Regional Mexicano" to 0xFF27856A, "Salsa" to 0xFFE8115B, "Jazz" to 0xFF477D95, "Clásica" to 0xFF7D4B32,
    "K-Pop" to 0xFF148A08, "Metal" to 0xFF503750, "Lo-fi" to 0xFF5179A1, "Workout" to 0xFF777777,
)

@Composable
fun SearchScreen() {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val vm: SearchViewModel = viewModel { SearchViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val c = LocalSpotifreeColors.current

    val actions2 = actions
    val strings = com.spotifree.app.ui.i18n.LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) { vm.onQuery(text); vm.submit(text) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("searchTitle"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            // Previous app: paste a YouTube / YouTube Music link straight from the clipboard
            OutlinePill(t("pasteYouTubeLink"), Icons.Default.Link, tint = c.accent, borderColor = c.accent.copy(alpha = 0.6f)) {
                val text = clipboard.getText()?.text.orEmpty()
                if (!actions2.openLink(text)) graph.messages.post(strings["noLinkInClipboard"], isError = true)
            }
        }
        TextField(
            value = ui.query,
            onValueChange = vm::onQuery,
            placeholder = { Text(t("searchPlaceholder"), color = Color(0xFF535353), fontWeight = FontWeight.SemiBold) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Black) },
            trailingIcon = {
                if (ui.query.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Default.Clear, null, tint = Color.Black) }
                else IconButton(onClick = {
                    runCatchingCancellable {
                        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, strings["searchPlaceholder"]))
                    }.onFailure { graph.messages.post(strings["voiceUnavailable"], isError = true) }
                }) { Icon(Icons.Default.Mic, t("voiceSearch"), tint = Color.Black) }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit(); focus.clearFocus() }),
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, cursorColor = Color.Black,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        // Filter pills are always visible (previous app); before searching they preselect the type
        val filters = listOf(null to t("filterAll"), SearchFilter.SONGS to t("filterSongs"), SearchFilter.ALBUMS to t("filterAlbums"),
            SearchFilter.ARTISTS to t("filterArtists"), SearchFilter.PLAYLISTS to t("filterPlaylists"), SearchFilter.VIDEOS to t("filterVideos"))
        PillRow(filters, ui.filter, PaddingValues(horizontal = 16.dp, vertical = 12.dp)) { vm.setFilter(it) }

        Box(Modifier.weight(1f)) {
            when {
                // Typing: suggestions + matches in the library
                ui.query.isNotBlank() && ui.query != ui.submitted -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.suggestions) { s ->
                        Row(Modifier.fillMaxWidth().clickable { vm.submit(s); focus.clearFocus() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = c.textSecondary)
                            Spacer(Modifier.width(16.dp))
                            Text(s, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.onQuery(s) }) { Icon(Icons.Default.NorthWest, null, tint = c.textSecondary) }
                        }
                    }
                    if (ui.local.isNotEmpty()) {
                        item { SectionTitle(t("inYourLibrary")) }
                        items(ui.local) { tr ->
                            TrackRow(tr, onClick = { actions.playFromSearch(tr) }, isCurrent = player.current?.id == tr.id,
                                onMore = { actions.showMenu(tr, MenuContext()) })
                        }
                    }
                }
                ui.loading -> LoadingBox()
                ui.submitted.isNotBlank() -> Results(ui, player.current?.id, onRetry = { vm.submit() })
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (ui.history.isNotEmpty()) {
                        item {
                            SectionTitle(t("searchHistory")) { TextButton(onClick = vm::clearHistory) { Text(t("clearHistory")) } }
                        }
                        items(ui.history.take(8)) { h ->
                            Row(Modifier.fillMaxWidth().clickable { vm.submit(h) }.padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, null, tint = c.textSecondary)
                                Spacer(Modifier.width(16.dp))
                                Text(h, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { vm.removeHistory(h) }) { Icon(Icons.Default.Close, null, tint = c.textSecondary) }
                            }
                        }
                    }
                    item { SectionTitle(t("browseAll")) }
                    items(GENRES.chunked(2)) { row ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { (name, color) ->
                                CategoryCard(name, Color(color), genreIcon(name), Modifier.weight(1f)) { vm.onQuery(name); vm.submit(name) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun genreIcon(name: String): ImageVector = when (name) {
    "Rock", "Metal" -> Icons.Default.Bolt
    "Electrónica" -> Icons.Default.GraphicEq
    "Hip Hop", "R&B" -> Icons.Default.Mic
    "Jazz", "Clásica" -> Icons.Default.Piano
    "Lo-fi" -> Icons.Default.Headphones
    "Workout" -> Icons.Default.FitnessCenter
    "Reggaeton", "Latina", "Salsa", "Regional Mexicano" -> Icons.Default.Celebration
    else -> Icons.Default.MusicNote
}

@Composable
private fun Results(ui: SearchUi, currentId: String?, onRetry: () -> Unit) {
    val actions = LocalActions.current
    val c = LocalSpotifreeColors.current
    val r = ui.results ?: SearchResults()
    val empty = r.songs.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty() && ui.local.isEmpty()
    if (empty) {
        EmptyState(Icons.Default.SearchOff, if (ui.error) t("searchFailed") else t("noResultsFound"), t("noResultsSub"), action = {
            if (ui.error) TextButton(onClick = onRetry) { Text(t("retry")) }
        })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Top result: first artist when searching everything
        val top = r.artists.firstOrNull()
        if (ui.filter == null && top != null) item {
            SectionTitle(t("topResult"))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.elevated)
                    .clickable { actions.openArtist(top.url ?: top.name) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(top.imageUrl, size = 88.dp, shape = CircleShape, placeholder = Icons.Default.Person)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(top.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t("artist").uppercase(), color = c.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textSecondary)
            }
        }
        if (r.songs.isNotEmpty()) {
            if (ui.filter == null) item { SectionTitle(t("songs")) }
            val list = if (ui.filter == null) r.songs.take(8) else r.songs
            items(list) { tr ->
                TrackRow(tr, onClick = { actions.playFromSearch(tr) }, isCurrent = currentId == tr.id,
                    onMore = { actions.showMenu(tr, MenuContext()) })
            }
        }
        if (r.artists.isNotEmpty() && ui.filter != SearchFilter.ARTISTS) item {
            com.spotifree.app.ui.common.Shelf(t("artists"), r.artists) { a ->
                ShelfCard(a.name, null, a.imageUrl, onClick = { actions.openArtist(a.url ?: a.name) }, circle = true, width = 120.dp)
            }
        } else if (r.artists.isNotEmpty()) items(r.artists) { a ->
            Row(Modifier.fillMaxWidth().clickable { actions.openArtist(a.url ?: a.name) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(a.imageUrl, size = 56.dp, shape = CircleShape)
                Spacer(Modifier.width(12.dp))
                Text(a.name, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (r.albums.isNotEmpty()) item {
            com.spotifree.app.ui.common.Shelf(t("albums"), r.albums) { a ->
                ShelfCard(a.title, listOfNotNull(a.artist.takeIf { it.isNotBlank() }, a.year).joinToString(" · "), a.coverUrl,
                    onClick = { actions.openCollection(a.url, a.title) })
            }
        }
        if (r.playlists.isNotEmpty()) item {
            com.spotifree.app.ui.common.Shelf(t("playlistsTitle"), r.playlists) { p ->
                ShelfCard(p.title, p.owner, p.coverUrl, onClick = { actions.openCollection(p.url, p.title) })
            }
        }
        if (ui.local.isNotEmpty()) {
            item { SectionTitle(t("inYourLibrary")) }
            items(ui.local) { tr ->
                TrackRow(tr, onClick = { actions.playFromSearch(tr) }, isCurrent = currentId == tr.id,
                    onMore = { actions.showMenu(tr, MenuContext()) })
            }
        }
    }
}

