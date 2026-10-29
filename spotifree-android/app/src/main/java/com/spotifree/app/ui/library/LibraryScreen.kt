package com.spotifree.app.ui.library

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
    var filter by remember { mutableStateOf(LibFilter.ALL) }
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("libraryTitle"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.nav.navigate(com.spotifree.app.ui.SearchRoute) }) { Icon(Icons.Default.Search, null) }
                IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibFilter.entries) { f ->
                    val label = when (f) {
                        LibFilter.ALL -> t("filterAll")
                        LibFilter.PLAYLISTS -> t("filterPlaylists")
                        LibFilter.ARTISTS -> t("filterArtists")
                        LibFilter.DOWNLOADED -> t("filterDownloaded")
                    }
                    FilterChip(selected = filter == f, onClick = { filter = if (filter == f) LibFilter.ALL else f }, label = { Text(label) }, shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = Color.Black))
                }
            }
        }
        if (filter == LibFilter.ALL || filter == LibFilter.PLAYLISTS) {
            item {
                LibraryRow(
                    title = t("likedSongs"), subtitle = LocalStrings.current.songs(likedCount),
                    gradient = listOf(Color(0xFF450AF5), Color(0xFFC4EFD9)), icon = Icons.Default.Favorite,
                ) { actions.openPlaylist(Playlist.LIKED_ID) }
            }
        }
        if (filter == LibFilter.ALL || filter == LibFilter.DOWNLOADED) {
            item {
                LibraryRow(title = t("downloadsTitle"), subtitle = LocalStrings.current.songs(doneIds.size),
                    gradient = listOf(c.accent, Color(0xFF0D3B2E)), icon = Icons.Default.Download) { actions.openPlaylist(PlaylistRoute.DOWNLOADS) }
                LibraryRow(title = t("onThisDevice"), subtitle = t("onThisDeviceSub"),
                    gradient = listOf(Color(0xFF535353), Color(0xFF181818)), icon = Icons.Default.PhoneAndroid) { actions.openPlaylist(PlaylistRoute.DEVICE) }
            }
        }
        if (filter == LibFilter.ALL || filter == LibFilter.PLAYLISTS) {
            items(playlists.filter { it.id != Playlist.LIKED_ID }, key = { "p-" + it.id }) { p ->
                val sub = buildString {
                    append(t("playlist"))
                    append(" · ")
                    append(LocalStrings.current.songs(p.trackCount))
                    if (p.youtubePlaylistId != null) append(" · YouTube")
                    if (p.isSpotifyImport) append(" · Spotify")
                }
                Row(
                    Modifier.fillMaxWidth().clickable { actions.openPlaylist(p.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(p.coverUrl, size = 64.dp, placeholder = Icons.Default.LibraryMusic)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (filter == LibFilter.ALL || filter == LibFilter.ARTISTS) {
            items(artists, key = { "a-" + it.key }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { actions.openArtist(a.url ?: a.name) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(a.imageUrl, size = 64.dp, shape = CircleShape, placeholder = Icons.Default.Person)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(t("artist"), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
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

@Composable
private fun LibraryRow(title: String, subtitle: String, gradient: List<Color>, icon: ImageVector, onClick: () -> Unit) {
    val c = LocalSpotifreeColors.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(gradient)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

