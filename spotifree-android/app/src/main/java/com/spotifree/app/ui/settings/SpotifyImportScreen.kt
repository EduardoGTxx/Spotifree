package com.spotifree.app.ui.settings

import com.spotifree.app.util.runCatchingCancellable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spotifree.app.data.importer.SpotifyAnalysis
import com.spotifree.app.data.importer.SpotifyImportOptions
import com.spotifree.app.data.importer.SpotifyImportResult
import com.spotifree.app.data.importer.SpotifyImporter
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.detail.DetailTopBar
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Imports the "Download your data" export from Spotify (ZIP or the JSON files inside):
 * playlists, liked songs, followed artists, hidden songs/artists and streaming history.
 */
@Composable
fun SpotifyImportScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val importer = remember { SpotifyImporter(context.applicationContext, graph.db, graph.library) }
    var busy by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<SpotifyAnalysis?>(null) }
    var result by remember { mutableStateOf<SpotifyImportResult?>(null) }
    var options by remember { mutableStateOf(SpotifyImportOptions()) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        error = null
        result = null
        busy = strings["analyzing"]
        scope.launch {
            runCatchingCancellable { importer.analyze(uris) { busy = it } }
                .onSuccess { a -> analysis = a; if (a.isEmpty) error = strings["spotifyNothingFound"] }
                .onFailure { error = strings.f("spotifyReadFailed", it.message ?: "") }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(t("importSpotifyData"), onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(t("spotifyImportIntro"), color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) }, enabled = busy == null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Icon(Icons.Default.FolderZip, null)
                Text("  " + t("chooseFiles"))
            }
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text(it)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val a = analysis
            if (a != null && !a.isEmpty && result == null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.elevated).padding(12.dp)) {
                    Text(t("spotifyFound"), style = MaterialTheme.typography.titleMedium)
                    CheckRow(t("spotifyPlaylists", a.playlists.size, a.playlists.sumOf { it.tracks.size }), options.playlists, a.playlists.isNotEmpty()) {
                        options = options.copy(playlists = it)
                    }
                    CheckRow(t("spotifyLiked", a.liked.size), options.liked, a.liked.isNotEmpty()) { options = options.copy(liked = it) }
                    CheckRow(t("spotifyArtists", a.artists.size), options.artists, a.artists.isNotEmpty()) { options = options.copy(artists = it) }
                    CheckRow(t("spotifyBanned", a.bannedTracks.size + a.bannedArtists.size), options.banned,
                        a.bannedTracks.isNotEmpty() || a.bannedArtists.isNotEmpty()) { options = options.copy(banned = it) }
                    val df = DateFormat.getDateInstance()
                    val from = a.historyFrom
                    val to = a.historyTo
                    val range = if (from != null && to != null) "${df.format(Date(from))} – ${df.format(Date(to))}" else ""
                    CheckRow(t("spotifyHistory", a.history.size, range), options.history, a.history.isNotEmpty()) { options = options.copy(history = it) }
                    if (a.ignored.isNotEmpty()) Text(t("spotifyIgnored", a.ignored.size), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    busy = strings["importing"]
                    scope.launch {
                        runCatchingCancellable { importer.import(a, options) { busy = it } }
                            .onSuccess { result = it }
                            .onFailure { error = strings.f("spotifyReadFailed", it.message ?: "") }
                        busy = null
                    }
                }, enabled = busy == null, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text(t("importNow")) }
            }
            result?.let { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.elevated).padding(12.dp)) {
                    Text(t("importDone"), style = MaterialTheme.typography.titleMedium, color = c.accent)
                    Text(t("spotifyResult", r.playlistsAdded, r.playlistsSkipped, r.likedAdded, r.artistsAdded, r.blockedAdded, r.historyAdded))
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text(t("done")) }
            }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
