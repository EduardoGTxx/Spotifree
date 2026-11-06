package com.spotifree.app.ui.library

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.common.ActionPill
import com.spotifree.app.ui.common.PillRow
import com.spotifree.app.ui.theme.LikedGradient

import com.spotifree.app.util.runCatchingCancellable

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.data.importer.CsvImporter
import com.spotifree.app.data.model.Playlist
import com.spotifree.app.data.model.PlaylistRef
import com.spotifree.app.data.remote.YouTubeIds
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.PlaylistRoute
import com.spotifree.app.ui.SpotifyImportRoute
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.LoadingBox
import com.spotifree.app.ui.common.MenuItem
import com.spotifree.app.ui.common.NameDialog
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.launch

private enum class LibFilter { ALL, PLAYLISTS, ARTISTS, DOWNLOADED }

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val playlists by remember { graph.library.observePlaylists() }.collectAsState(initial = emptyList())
    val artists by remember { graph.library.observeFollowedArtists() }.collectAsState(initial = emptyList())
    val likedCount by remember { graph.db.likes().observeCount() }.collectAsState(initial = 0)
    val doneIds by graph.downloads.doneIds.collectAsStateWithLifecycle()
    val account by graph.account.account.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibFilter.ALL) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    // Automatic mixes of the previous app: "On repeat this month" and "Recently added"
    var onRepeat by remember { mutableStateOf<List<Track>>(emptyList()) }
    val likedRecent by remember { graph.library.observeLiked() }.collectAsState(initial = emptyList())
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val monthStart = java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        onRepeat = runCatchingCancellable {
            val top = graph.db.history().topTracks(monthStart, now + 1, 40).filter { it.plays >= 2 }
            graph.library.tracksByIds(top.map { it.trackId })
        }.getOrDefault(emptyList())
    }
    var showAdd by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var ytLists by remember { mutableStateOf<List<PlaylistRef>?>(null) }
    var ytLoading by remember { mutableStateOf(false) }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatchingCancellable {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val (title, tracks) = CsvImporter.parse(text, name)
                graph.library.createPlaylist(title, tracks, isSpotifyImport = true)
                graph.messages.post(strings.f("csvImported", title, tracks.size))
            }.onFailure { graph.messages.post(strings["csvImportFailed"], isError = true) }
        }
    }

    fun importYouTube(ref: PlaylistRef) = scope.launch {
        graph.messages.post(strings.f("importingPlaylist", ref.title))
        runCatchingCancellable {
            val listId = YouTubeIds.playlistId(ref.url) ?: return@launch
            val tracks = graph.account.playlistTracks(listId)
            val id = "yt-pl-$listId"
            val existing = graph.db.playlists().get(id)
            if (existing != null) graph.library.replacePlaylistTracks(id, tracks)
            else graph.library.createPlaylist(ref.title, tracks, id = id, youtubePlaylistId = listId, coverUrl = ref.coverUrl)
            graph.messages.post(strings.f("playlistImported", ref.title, tracks.size))
        }.onFailure { graph.messages.post(strings["importFailed"], isError = true) }
    }

    val userLists = playlists.filter { it.id != Playlist.LIKED_ID }.let { l -> if (alphabetical) l.sortedBy { it.title.lowercase() } else l }
    val shownArtists = if (alphabetical) artists.sortedBy { it.name.lowercase() } else artists
    val showLists = filter == LibFilter.ALL || filter == LibFilter.PLAYLISTS
    val showArtists = filter == LibFilter.ALL || filter == LibFilter.ARTISTS
    val showDownloads = filter == LibFilter.ALL || filter == LibFilter.DOWNLOADED

    val entries = buildList<LibEntry> {
        if (showLists) add(LibEntry(strings["likedSongs"], strings.songs(likedCount), null, LikedGradient, Icons.Default.Favorite, pinned = true) {
            actions.openPlaylist(Playlist.LIKED_ID)
        })
        if (showDownloads) {
            add(LibEntry(strings["downloadsTitle"], strings.songs(doneIds.size), null, listOf(c.accent, Color(0xFF0D3B2E)), Icons.Default.Download, pinned = true) {
                actions.openPlaylist(PlaylistRoute.DOWNLOADS)
            })
            add(LibEntry(strings["onThisDevice"], strings["onThisDeviceSub"], null, listOf(Color(0xFF535353), Color(0xFF181818)), Icons.Default.PhoneAndroid) {
                actions.openPlaylist(PlaylistRoute.DEVICE)
            })
        }
        if (showLists) userLists.forEach { p ->
            val sub = buildString {
                append(strings["playlist"])
                append(" · ")
                append(strings.songs(p.trackCount))
                if (p.youtubePlaylistId != null) append(" · YouTube")
                if (p.isSpotifyImport) append(" · Spotify")
            }
            add(LibEntry(p.title, sub, p.coverUrl, null, Icons.Default.LibraryMusic) { actions.openPlaylist(p.id) })
        }
        if (showArtists) shownArtists.forEach { a ->
            add(LibEntry(a.name, strings["artist"], a.imageUrl, null, Icons.Default.Person, circle = true) { actions.openArtist(a.url ?: a.name) })
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("libraryTitle"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.nav.navigate(com.spotifree.app.ui.StatsRoute) }) { Icon(Icons.Default.BarChart, t("statsTitle"), tint = c.accent) }
                IconButton(onClick = { actions.nav.navigate(com.spotifree.app.ui.SearchRoute) }) { Icon(Icons.Default.Search, null) }
                IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null) }
            }
            val chips = listOf(
                LibFilter.ALL to t("filterAll"),
                LibFilter.PLAYLISTS to t("filterPlaylists"),
                LibFilter.ARTISTS to t("filterArtists"),
                LibFilter.DOWNLOADED to "${t("filterDownloaded")} (${doneIds.size})",
            )
            PillRow(chips, filter) { f -> filter = if (filter == f) LibFilter.ALL else f }
        }
        // Automatic mixes (previous app)
        if (showLists && (onRepeat.isNotEmpty() || likedRecent.isNotEmpty())) item {
            Text(t("smartMixes").uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = c.textSecondary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRepeat.isNotEmpty()) item {
                    MixCard(t("mixOnRepeat"), t("mixOnRepeatDesc", onRepeat.size), listOf(Color(0xFF2979FF), Color(0xFF0D47A1))) { actions.play(onRepeat) }
                }
                if (likedRecent.isNotEmpty()) item {
                    val list = likedRecent.take(50)
                    MixCard(t("mixRecentlyAdded"), t("mixRecentlyAddedDesc", list.size), listOf(Color(0xFF1DB954), Color(0xFF0B6B30))) { actions.play(list) }
                }
                if (likedRecent.size >= 5) item {
                    MixCard(t("mixShuffleLiked"), t("mixShuffleLikedDesc"), listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))) { actions.play(likedRecent, 0, shuffle = true) }
                }
            }
        }
        // Sort + list/grid toggle
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(CircleShape).clickable { alphabetical = !alphabetical }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (alphabetical) t("sortAlphabetical") else t("sortRecents"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, null, tint = c.textSecondary)
                }
            }
        }
        if (grid) {
            items(entries.chunked(2)) { row ->
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { e -> GridEntry(e, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            items(entries, key = { it.title + it.subtitle + it.circle }) { e -> ListEntry(e) }
        }
        // Empty library: the previous app's call to action
        if (filter == LibFilter.ALL && userLists.isEmpty() && artists.isEmpty()) item {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text(t("libraryEmptyTitle"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text(t("libraryEmptySub"), color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(t("searchSongsCta"), Icons.Default.Search) { actions.nav.navigate(com.spotifree.app.ui.SearchRoute) }
                    ActionPill(t("importCsv"), Icons.Default.FileUpload, primary = false) {
                        csvPicker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "application/octet-stream"))
                    }
                    ActionPill(t("importSpotifyData"), Icons.Default.CloudDownload, primary = false) { actions.nav.navigate(SpotifyImportRoute) }
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
            Column(Modifier.navigationBarsPadding()) {
                MenuItem(Icons.Default.PlaylistAdd, t("newPlaylist")) { showAdd = false; creating = true }
                MenuItem(Icons.Default.FileUpload, t("importCsv")) {
                    showAdd = false
                    csvPicker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "application/octet-stream"))
                }
                MenuItem(Icons.Default.CloudDownload, t("importSpotifyData")) { showAdd = false; actions.nav.navigate(SpotifyImportRoute) }
                MenuItem(Icons.Default.SmartDisplay, if (account != null) t("importFromYouTubeAccount") else t("connectYouTubeToImport")) {
                    showAdd = false
                    if (account == null) actions.nav.navigate(com.spotifree.app.ui.SettingsRoute)
                    else {
                        ytLoading = true
                        ytLists = emptyList()
                        scope.launch {
                            ytLists = runCatchingCancellable { graph.account.playlists() }.getOrElse {
                                graph.messages.post(strings["importFailed"], isError = true); null
                            }
                            ytLoading = false
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog(t("newPlaylist"), "", onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch {
                val id = graph.library.createPlaylist(name)
                actions.openPlaylist(id)
            }
        }
    }
    ytLists?.let { lists ->
        ModalBottomSheet(onDismissRequest = { ytLists = null }, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
            Text(t("yourYouTubePlaylists"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (ytLoading) LoadingBox()
            else if (lists.isEmpty()) Text(t("noPlaylistsFound"), color = c.textSecondary, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp).navigationBarsPadding()) {
                items(lists) { ref ->
                    Row(
                        Modifier.fillMaxWidth().clickable { importYouTube(ref); ytLists = null }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(ref.coverUrl, size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ref.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ref.owner, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.Download, null, tint = c.textSecondary)
                    }
                }
            }
        }
    }
}

private data class LibEntry(
    val title: String,
    val subtitle: String,
    val image: String?,
    val gradient: List<Color>?,
    val icon: ImageVector,
    val circle: Boolean = false,
    val pinned: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun EntryCover(e: LibEntry, size: androidx.compose.ui.unit.Dp?, modifier: Modifier = Modifier) {
    val shape = if (e.circle) CircleShape else RoundedCornerShape(6.dp)
    if (e.gradient != null && e.image == null) {
        Box(modifier.let { if (size != null) it.size(size) else it }.clip(shape).background(Brush.linearGradient(e.gradient)), contentAlignment = Alignment.Center) {
            Icon(e.icon, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
        }
    } else Artwork(e.image, modifier, size = size, shape = shape, placeholder = e.icon)
}

@Composable
private fun ListEntry(e: LibEntry) {
    val c = LocalSpotifreeColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = e.onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryCover(e, 64.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.pinned) {
                    Icon(Icons.Default.PushPin, null, tint = c.accent, modifier = Modifier.size(13.dp).rotate(45f))
                    Spacer(Modifier.width(4.dp))
                }
                Text(e.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GridEntry(e: LibEntry, modifier: Modifier) {
    val c = LocalSpotifreeColors.current
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = e.onClick).padding(4.dp)) {
        EntryCover(e, null, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            textAlign = if (e.circle) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        Text(e.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textSecondary, fontSize = 12.sp,
            textAlign = if (e.circle) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
    }
}

/** Gradient card of an automatic mix with a round play button (previous app). */
@Composable
private fun MixCard(title: String, subtitle: String, colors: List<Color>, onPlay: () -> Unit) {
    Column(
        Modifier.width(176.dp).height(100.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(colors))
            .clickable(onClick = onPlay).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
