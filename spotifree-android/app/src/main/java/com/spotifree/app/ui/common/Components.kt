package com.spotifree.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.spotifree.app.data.db.DownloadState
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.theme.LocalSpotifreeColors

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMs(ms: Long) = formatDuration((ms / 1000).toInt()).ifEmpty { "0:00" }

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp? = 48.dp,
    shape: Shape = RoundedCornerShape(4.dp),
    placeholder: ImageVector = Icons.Default.MusicNote,
) {
    val c = LocalSpotifreeColors.current
    val base = (if (size != null) modifier.size(size) else modifier).clip(shape).background(c.elevated)
    if (url.isNullOrBlank()) {
        Box(base, contentAlignment = Alignment.Center) {
            Icon(placeholder, null, tint = c.textSecondary, modifier = Modifier.fillMaxSize(0.45f))
        }
        return
    }
    val context = LocalContext.current
    val request = remember(url) { ImageRequest.Builder(context).data(url).build() }
    // YouTube video thumbnails (hqdefault) are 4:3 with black bars around a 16:9 frame: zoom in
    // so the square shows the picture, like the official app.
    val zoom = if (url.contains("i.ytimg.com") && (url.contains("hqdefault") || url.contains("sddefault"))) 1.34f else 1f
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = base.then(if (zoom != 1f) Modifier.graphicsLayer(scaleX = zoom, scaleY = zoom) else Modifier),
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(placeholder, null, tint = c.textSecondary, modifier = Modifier.fillMaxSize(0.45f))
            }
        },
    )
}

/** Small status icon: downloaded (accent), downloading (spinner). */
@Composable
fun DownloadBadge(trackId: String, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val all by graph.downloads.all.collectAsStateWithLifecycle()
    val d = all[trackId] ?: return
    val c = LocalSpotifreeColors.current
    when (d.state) {
        DownloadState.DONE -> Icon(Icons.Default.ArrowDownward, null, tint = Color.Black,
            modifier = modifier.size(14.dp).clip(CircleShape).background(c.accent).padding(2.dp))
        DownloadState.RUNNING, DownloadState.QUEUED -> CircularProgressIndicator(
            progress = { d.progress.coerceIn(0.02f, 1f) }, modifier = modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
        else -> Unit
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    index: Int? = null,
    showArtwork: Boolean = true,
    onMore: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = onMore,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalSpotifreeColors.current
    val graph = LocalGraph.current
    val liked by graph.library.likedIds.collectAsStateWithLifecycle()
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("$index", color = if (isCurrent) c.accent else c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.width(8.dp))
        }
        if (showArtwork) {
            // Imported songs have no cover until played: look one up while the row is visible
            if (track.artworkUrl == null && !track.isLocal) LaunchedEffect(track.id) { graph.artwork.ensureCover(track) }
            Artwork(track.artworkUrl, size = 48.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) c.accent else Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.id in liked) {
                    Icon(Icons.Default.Favorite, null, tint = c.accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                DownloadBadge(track.id, Modifier.padding(end = 4.dp))
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        trailing?.invoke()
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, null, tint = c.textSecondary) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

/** Square/circle card used in horizontal shelves (albums, playlists, artists, mixes). */
@Composable
fun ShelfCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    circle: Boolean = false,
    width: Dp = 140.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val c = LocalSpotifreeColors.current
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Artwork(imageUrl, size = width - 8.dp, shape = if (circle) CircleShape else RoundedCornerShape(6.dp),
            placeholder = if (circle) Icons.Default.Person else Icons.Default.MusicNote)
        Spacer(Modifier.height(8.dp))
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            textAlign = if (circle) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                textAlign = if (circle) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun <T> Shelf(title: String, items: List<T>, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items) { card(it) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = LocalSpotifreeColors.current
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxWidth().height(200.dp)) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalSpotifreeColors.current.accent) }
}

@Composable
fun CheckMark(modifier: Modifier = Modifier) {
    Icon(Icons.Default.CheckCircle, null, tint = LocalSpotifreeColors.current.accent, modifier = modifier.size(20.dp))
}
