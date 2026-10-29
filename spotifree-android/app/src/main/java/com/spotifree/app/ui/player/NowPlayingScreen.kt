package com.spotifree.app.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.ui.unit.sp
import com.spotifree.app.data.db.DownloadState
import com.spotifree.app.ui.common.SeekBar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.formatMs
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import com.spotifree.app.ui.theme.onColor

private enum class PlayerSheet { NONE, QUEUE, SLEEP, EFFECTS, DEVICES }

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val liked by graph.library.likedIds.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val c = LocalSpotifreeColors.current
    val cover by rememberCoverColor(track.artworkUrl, c.elevated)
    val animated by animateColorAsState(cover, tween(600), label = "bg")
    var showLyrics by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(PlayerSheet.NONE) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val dragY = remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(animated, if (c.amoled) Color.Black else c.background)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragY.floatValue > 200) onClose()
                        dragY.floatValue = 0f
                    },
                    onVerticalDrag = { _, d -> dragY.floatValue += d },
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("nowPlaying").uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    Text(track.album.ifBlank { track.artist }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { actions.showMenu(track, MenuContext()) }) { Icon(Icons.Default.MoreVert, null) }
            }

            if (landscape) {
                Row(Modifier.weight(1f).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Crossfade(showLyrics, label = "lyrics") { lyr ->
                            if (lyr) LyricsView(track, Modifier.fillMaxSize()) else CoverArt(track.artworkUrl, onSwipe = { if (it < 0) graph.player.next() else graph.player.previous() })
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        TitleRow(track.title, track.artist, track.id in liked, onLike = { actions.toggleLike(track) }, onArtist = { onClose(); actions.openArtist(track) })
                        Controls(onSheet = { sheet = it }, showLyrics = showLyrics, onToggleLyrics = { showLyrics = !showLyrics })
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Crossfade(showLyrics, label = "lyrics") { lyr ->
                        if (lyr) LyricsView(track, Modifier.fillMaxSize()) else CoverArt(track.artworkUrl, onSwipe = { if (it < 0) graph.player.next() else graph.player.previous() })
                    }
                }
                Column(Modifier.padding(horizontal = 24.dp)) {
                    TitleRow(track.title, track.artist, track.id in liked, onLike = { actions.toggleLike(track) }, onArtist = { onClose(); actions.openArtist(track) })
                    Controls(onSheet = { sheet = it }, showLyrics = showLyrics, onToggleLyrics = { showLyrics = !showLyrics })
                }
            }
        }
    }

    when (sheet) {
        PlayerSheet.QUEUE -> QueueSheet(onDismiss = { sheet = PlayerSheet.NONE })
        PlayerSheet.SLEEP -> SleepTimerSheet(onDismiss = { sheet = PlayerSheet.NONE })
        PlayerSheet.EFFECTS -> EffectsSheet(onDismiss = { sheet = PlayerSheet.NONE })
        PlayerSheet.DEVICES -> DevicesSheet(onDismiss = { sheet = PlayerSheet.NONE })
        PlayerSheet.NONE -> Unit
    }
}

@Composable
private fun CoverArt(url: String?, onSwipe: (Float) -> Unit) {
    val drag = remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Artwork(
            url,
            size = side,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .shadow(16.dp, RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(drag.floatValue) > 120) onSwipe(drag.floatValue)
                            drag.floatValue = 0f
                        },
                        onHorizontalDrag = { _, d -> drag.floatValue += d },
                    )
                },
        )
    }
}

@Composable
private fun TitleRow(title: String, artist: String, liked: Boolean, onLike: () -> Unit, onArtist: () -> Unit) {
    val c = LocalSpotifreeColors.current
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val track = graph.player.state.collectAsStateWithLifecycle().value.current
    val downloads by graph.downloads.all.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                modifier = Modifier.basicMarquee(initialDelayMillis = 2000))
            Text(artist, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onArtist))
        }
        // Previous app: download button beside the heart
        if (track != null && !track.isLocal) {
            val d = downloads[track.id]
            IconButton(onClick = { if (d?.state != DownloadState.DONE) actions.download(track) }) {
                when (d?.state) {
                    DownloadState.DONE -> Icon(Icons.Default.DownloadDone, t("downloaded"), tint = c.accent, modifier = Modifier.size(28.dp))
                    DownloadState.RUNNING, DownloadState.QUEUED -> CircularProgressIndicator(
                        progress = { d.progress.coerceIn(0.02f, 1f) }, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = c.accent)
                    else -> Icon(Icons.Outlined.DownloadForOffline, t("download"), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
                }
            }
        }
        IconButton(onClick = onLike) {
            Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (liked) c.accent else Color.White, modifier = Modifier.size(28.dp))
        }
    }
}


@Composable
private fun Controls(onSheet: (PlayerSheet) -> Unit, showLyrics: Boolean, onToggleLyrics: () -> Unit) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val position by graph.player.position.collectAsStateWithLifecycle()
    val c = LocalSpotifreeColors.current
    var seeking by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = seeking ?: (position.toFloat() / duration).coerceIn(0f, 1f)

    SeekBar(
        fraction = fraction,
        onPreview = { seeking = it },
        onSeek = { graph.player.seekTo((it * duration).toLong()) },
        modifier = Modifier.padding(top = 10.dp),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatMs(seeking?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.weight(1f))
        Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { graph.player.toggleShuffle() }) {
            Icon(Icons.Default.Shuffle, null, tint = if (state.shuffle) c.accent else Color.White)
        }
        IconButton(onClick = { graph.player.previous() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(40.dp))
        }
        Box(
            Modifier.size(68.dp).shadow(12.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { graph.player.togglePlay() }, modifier = Modifier.fillMaxSize()) {
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(40.dp))
            }
        }
        IconButton(onClick = { graph.player.next() }, enabled = state.hasNext || state.queue.isNotEmpty(), modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = { graph.player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null,
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) c.accent else Color.White,
            )
        }
    }
    // Previous app: labelled pills (speed, sleep timer, lyrics) between devices and queue
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSheet(PlayerSheet.DEVICES) }) {
            Icon(if (state.isCasting) Icons.Default.Cast else Icons.Default.Devices, t("connectDevice"),
                tint = if (state.isCasting) c.accent else Color.White.copy(alpha = 0.85f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            val speed = if (state.speed == 1f) "1x" else "%.2fx".format(state.speed).replace(".00x", "x").replace("0x", "x")
            PlayerPill(speed, null, active = state.speed != 1f) { onSheet(PlayerSheet.EFFECTS) }
            PlayerPill(null, Icons.Default.Bedtime, active = state.sleepMode.isNotEmpty()) { onSheet(PlayerSheet.SLEEP) }
            PlayerPill(null, Icons.Default.GraphicEq, active = false) { onSheet(PlayerSheet.EFFECTS) }
            PlayerPill(if (showLyrics) t("hideLyrics") else t("lyrics"), null, active = showLyrics, onClick = onToggleLyrics)
        }
        IconButton(onClick = { onSheet(PlayerSheet.QUEUE) }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, t("queue"), tint = Color.White.copy(alpha = 0.85f)) }
    }
}

@Composable
private fun PlayerPill(text: String?, icon: androidx.compose.ui.graphics.vector.ImageVector?, active: Boolean, onClick: () -> Unit) {
    val c = LocalSpotifreeColors.current
    Row(
        Modifier.height(32.dp).clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = if (active) c.accent else Color.White, modifier = Modifier.size(16.dp))
        if (text != null) Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 1, modifier = Modifier.padding(start = if (icon != null) 4.dp else 0.dp))
    }
}
