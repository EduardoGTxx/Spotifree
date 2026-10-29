package com.spotifree.app.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.TextUnit
import com.spotifree.app.data.lyrics.LyricsSync
import com.spotifree.app.data.model.LyricWord
import kotlinx.coroutines.delay

import com.spotifree.app.util.runCatchingCancellable

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.data.lyrics.LrcParser
import com.spotifree.app.data.lyrics.LyricsResult
import com.spotifree.app.data.lyrics.Romanizer
import com.spotifree.app.data.model.LyricLine
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.common.LoadingBox
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.launch

@Composable
fun LyricsView(track: Track, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val strings = LocalStrings.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    var result by remember(track.id) { mutableStateOf<LyricsResult?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var translation by remember(track.id) { mutableStateOf<List<String>?>(null) }
    var translating by remember { mutableStateOf(false) }
    var showTranslation by remember(track.id) { mutableStateOf(false) }

    LaunchedEffect(track.id, reload) {
        result = null
        result = runCatchingCancellable {
            graph.lyrics.fetch(track.title, track.artist, track.durationSec, track.youtubeId, track.album, force = reload > 0)
        }.getOrDefault(LyricsResult(null, notFound = false, networkError = true))
    }

    val res = result
    val lyrics = res?.lyrics
    Column(modifier) {
        if (lyrics != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = showTranslation,
                    onClick = {
                        if (showTranslation) showTranslation = false
                        else if (translation != null) showTranslation = true
                        else {
                            translating = true
                            scope.launch {
                                translation = runCatchingCancellable { graph.lyrics.translate(lyrics.lines, strings.lang).first }.getOrNull()
                                translating = false
                                showTranslation = translation != null
                                if (translation == null) graph.messages.post(strings["translationFailed"], isError = true)
                            }
                        }
                    },
                    label = { Text(if (translating) t("translating") else t("translate")) },
                    leadingIcon = { Icon(Icons.Default.Translate, null) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent.copy(alpha = 0.3f)),
                )
                FilterChip(
                    selected = settings.lyricsRomanization,
                    onClick = { scope.launch { graph.settings.update { it.copy(lyricsRomanization = !it.lyricsRomanization) } } },
                    label = { Text("Aa→ア") },
                    modifier = Modifier.padding(start = 6.dp),
                )
                IconButton(onClick = {
                    val order = listOf("sm", "md", "lg", "xl")
                    val next = order[(order.indexOf(settings.lyricsFontSize) + 1) % order.size]
                    scope.launch { graph.settings.update { it.copy(lyricsFontSize = next) } }
                }) { Icon(Icons.Default.FormatSize, null) }
            }
            if (lyrics.source != null) {
                Text(t("lyricsSource", lyrics.source), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                res == null -> LoadingBox(Modifier.fillMaxSize())
                lyrics == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (res.networkError) t("lyricsNetworkError") else t("noLyrics"), color = Color.White.copy(alpha = 0.7f))
                    TextButton(onClick = { reload++ }) {
                        Icon(Icons.Default.Refresh, null)
                        Text(t("retry"))
                    }
                }
                else -> LyricsLines(lyrics.lines, lyrics.isSynced, if (showTranslation) translation else null, settings.lyricsRomanization,
                    settings.lyricsKaraoke, settings.lyricsFontSize)
            }
        }
    }
}

@Composable
private fun LyricsLines(lines: List<LyricLine>, synced: Boolean, translation: List<String>?, romanize: Boolean, karaoke: Boolean, fontSize: String) {
    val graph = LocalGraph.current
    val c = LocalSpotifreeColors.current
    // Read the clock inside derivedStateOf: the active line changes a few times per minute, so the
    // list only recomposes then (the per-frame value is read by the karaoke line alone).
    val position = rememberSmoothPosition()
    val active by remember(lines, synced) {
        derivedStateOf { if (synced) LrcParser.activeIndex(lines, position.value / 1000.0) else -1 }
    }
    val listState = rememberLazyListState()
    val size = when (fontSize) {
        "sm" -> 18.sp
        "lg" -> 26.sp
        "xl" -> 30.sp
        else -> 22.sp
    }

    // Manual scrolling pauses auto-follow; it resumes after a few seconds without touching the list
    var dragging by remember { mutableStateOf(false) }
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    lastUserScrollAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }
    LaunchedEffect(active, dragging, lastUserScrollAt) {
        if (active < 0 || dragging) return@LaunchedEffect
        delay(LyricsSync.holdRemaining(SystemClock.elapsedRealtime(), lastUserScrollAt))
        if (!LyricsSync.mayAutoScroll(SystemClock.elapsedRealtime(), dragging, lastUserScrollAt)) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        listState.animateScrollToItem(active, -(viewport / 3))
    }
    val activeVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == active } } }

    val romanized = remember(lines, romanize) { if (romanize) lines.map { Romanizer.romanize(it.text) } else null }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 48.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lines) { i, line ->
                val isActive = i == active
                val isPast = synced && i < active
                val color by animateColorAsState(
                    when {
                        !synced -> Color.White
                        isActive -> Color.White
                        isPast -> Color.White.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.35f)
                    }, label = "line",
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = synced) {
                            lastUserScrollAt = 0L
                            graph.player.seekTo((line.timeSec * 1000).toLong())
                        }
                        .padding(vertical = 8.dp),
                ) {
                    val words = line.words
                    if (isActive && karaoke && !words.isNullOrEmpty()) {
                        KaraokeLine(words, position, size)
                    } else {
                        Text(if (line.isInstrumental) LrcParser.INSTRUMENTAL else line.text, color = color, fontSize = size, fontWeight = FontWeight.Bold, lineHeight = size * 1.25f)
                    }
                    romanized?.getOrNull(i)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                        Text(it, color = color.copy(alpha = color.alpha * 0.8f), fontSize = size * 0.65f)
                    }
                    translation?.getOrNull(i)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                        Text(it, color = c.accent.copy(alpha = if (isActive || !synced) 1f else 0.6f), fontSize = size * 0.65f, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        // Back to the line being sung after scrolling away from it
        if (synced && active >= 0 && lastUserScrollAt > 0L && !activeVisible && !dragging) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(CircleShape).background(Color.White)
                    .clickable { lastUserScrollAt = 0L }.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.MyLocation, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("lyricsBackToLine"), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

/** The line being sung, filled word by word; the only composable that reads the clock every frame. */
@Composable
private fun KaraokeLine(words: List<LyricWord>, position: State<Long>, size: TextUnit) {
    val timeSec = position.value / 1000.0
    Text(
        buildAnnotatedString {
            words.forEach { w ->
                val sung = w.timeSec <= timeSec
                withStyle(SpanStyle(color = if (sung) Color.White else Color.White.copy(alpha = 0.4f))) { append(w.text) }
            }
        },
        fontSize = size, fontWeight = FontWeight.Bold, lineHeight = size * 1.25f,
    )
}
