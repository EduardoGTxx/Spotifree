package com.spotifree.app.ui.settings

import com.spotifree.app.util.runCatchingCancellable

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.data.db.DownloadState
import com.spotifree.app.data.update.AppUpdate
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.EmptyState
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.detail.DetailTopBar
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(update: AppUpdate, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (progress < 0f) onDismiss() },
        title = { Text(strings["updateAvailable"] + " · v" + update.version) },
        text = {
            Column {
                Text(update.title, style = MaterialTheme.typography.titleSmall)
                update.notes.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (progress >= 0f) {
                    Text(strings["downloadingUpdate"], modifier = Modifier.padding(top = 12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress < 0f, onClick = {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    runCatchingCancellable {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    error = strings["allowInstallFirst"]
                    return@TextButton
                }
                progress = 0f
                error = null
                scope.launch {
                    runCatchingCancellable { graph.updates.downloadAndInstall(update) { p -> progress = p } }
                        .onSuccess { onDismiss() }
                        .onFailure {
                            progress = -1f
                            error = if (it is SecurityException) strings["updateChecksumFailed"] else strings.f("updateFailed", it.message ?: "")
                        }
                }
            }) { Text(strings["updateNow"]) }
        },
        dismissButton = { TextButton(enabled = progress < 0f, onClick = onDismiss) { Text(strings["later"]) } },
    )
}

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val artists by remember { graph.library.observeBlockedArtists() }.collectAsState(initial = emptyList())
    val tracks by remember { graph.library.observeBlockedTracks() }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(t("hiddenContent"), onBack)
        if (artists.isEmpty() && tracks.isEmpty()) {
            EmptyState(Icons.Default.VisibilityOff, t("nothingHidden"), t("hiddenContentDesc"))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (artists.isNotEmpty()) item { SectionTitle(t("hiddenArtists")) }
            items(artists, key = { "a" + it.key }) { a ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { graph.library.unblockArtist(a.key) } }) { Text(t("unhide")) }
                }
            }
            if (tracks.isNotEmpty()) item { SectionTitle(t("hiddenSongs")) }
            items(tracks, key = { "t" + it.trackId }) { tr ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr.title.ifBlank { tr.trackId }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tr.artist.isNotBlank()) Text(tr.artist, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { scope.launch { graph.library.unblockTrack(tr.trackId) } }) { Text(t("unhide")) }
                }
            }
        }
    }
}

/** Every download with its state: retry failed ones, cancel running ones, delete. */
@Composable
fun DownloadsManagerScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val all by graph.downloads.all.collectAsStateWithLifecycle()
    val rows = remember(all) { all.values.sortedWith(compareBy({ it.state == DownloadState.DONE }, { -it.savedAt })) }
    val titles = remember { mutableStateOf<Map<String, com.spotifree.app.data.model.Track>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(rows.size) {
        titles.value = graph.library.tracksByIds(rows.map { it.trackId }).associateBy { it.id }
    }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(t("manageDownloads"), onBack)
        if (rows.isEmpty()) {
            EmptyState(Icons.Default.Delete, t("emptyDownloadedTitle"), t("emptyDownloadedSub"))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it.trackId }) { d ->
                val tr = titles.value[d.trackId]
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(tr?.artworkUrl, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr?.title ?: d.trackId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when (d.state) {
                            DownloadState.DONE -> "%.1f MB".format(d.sizeBytes / 1048576.0) + if (!d.isManual) " · " + t("autoCached") else ""
                            DownloadState.RUNNING -> t("downloadingPct", (d.progress * 100).toInt())
                            DownloadState.QUEUED -> t("queued")
                            else -> t("downloadFailed") + (d.error?.let { " · $it" } ?: "")
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall,
                            color = if (d.state == DownloadState.FAILED) MaterialTheme.colorScheme.error else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.state == DownloadState.RUNNING) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    when (d.state) {
                        DownloadState.FAILED -> IconButton(onClick = { tr?.let { scope.launch { graph.downloads.enqueue(it) } } }) {
                            Icon(Icons.Default.Refresh, null, tint = c.accent)
                        }
                        DownloadState.RUNNING, DownloadState.QUEUED -> IconButton(onClick = { scope.launch { graph.downloads.cancel(d.trackId) } }) {
                            Icon(Icons.Default.Close, null)
                        }
                        else -> Unit
                    }
                    if (d.state == DownloadState.FAILED) Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    IconButton(onClick = { scope.launch { graph.downloads.remove(d.trackId) } }) { Icon(Icons.Default.Delete, null, tint = c.textSecondary) }
                }
            }
        }
    }
}
