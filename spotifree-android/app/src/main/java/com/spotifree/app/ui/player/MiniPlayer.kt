package com.spotifree.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.theme.LocalSpotifreeColors

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val position by graph.player.position.collectAsStateWithLifecycle()
    val liked by graph.library.likedIds.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val c = LocalSpotifreeColors.current
    val bg by rememberCoverColor(track.artworkUrl, c.elevated)
    val drag = remember { mutableFloatStateOf(0f) }
    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .clickable(onClick = onOpen)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag.floatValue < -120) graph.player.next() else if (drag.floatValue > 120) graph.player.previous()
                            drag.floatValue = 0f
                        },
                        onHorizontalDrag = { _, d -> drag.floatValue += d },
                    )
                },
        ) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artworkUrl, size = 42.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                if (state.isCasting) Icon(Icons.Default.Cast, null, tint = c.accent, modifier = Modifier.padding(horizontal = 4.dp).size(20.dp))
                IconButton(onClick = { actions.toggleLike(track) }) {
                    val isLiked = track.id in liked
                    Icon(if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isLiked) c.accent else Color.White)
                }
                IconButton(onClick = { graph.player.togglePlay() }) {
                    if (state.isBuffering && state.isPlaying.not()) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
            val progress = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
                drawStopIndicator = {},
            )
        }
    }
}
