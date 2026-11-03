package com.spotifree.app.ui.player

import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.data.lyrics.LrcParser
import com.spotifree.app.data.lyrics.LyricsResult
import com.spotifree.app.data.lyrics.LyricsSync
import com.spotifree.app.data.lyrics.Romanizer
import com.spotifree.app.data.model.LyricLine
import com.spotifree.app.data.model.LyricWord
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.LoadingBox
import com.spotifree.app.ui.common.SeekBar
import com.spotifree.app.ui.common.formatMs
import com.spotifree.app.ui.common.rememberTrackArtwork
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import com.spotifree.app.util.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

internal data class LyricsPalette(
    val top: Color,
    val mid: Color,
    val deep: Color,
    val bottom: Color,
    val glow: Color,
    val accentText: Color,
)

internal val LYRICS_PALETTES = listOf(
    // 1. Violet / Royal Purple
    LyricsPalette(
        top = Color(0xFF6B3FA0),
        mid = Color(0xFF3B1E58),
        deep = Color(0xFF1A0D2A),
        bottom = Color(0xFF0B0614),
        glow = Color(0xFF8E4EC6),
        accentText = Color(0xFFE0B0FF),
    ),
    // 2. Sapphire / Deep Ocean Blue
    LyricsPalette(
        top = Color(0xFF1E56A0),
        mid = Color(0xFF102E5A),
        deep = Color(0xFF08162C),
        bottom = Color(0xFF040A14),
        glow = Color(0xFF3B82F6),
        accentText = Color(0xFFB3D4FF),
    ),
    // 3. Emerald / Forest Teal
    LyricsPalette(
        top = Color(0xFF16695B),
        mid = Color(0xFF0B3B33),
        deep = Color(0xFF051C18),
        bottom = Color(0xFF020D0B),
        glow = Color(0xFF10B981),
        accentText = Color(0xFFA7F3D0),
    ),
    // 4. Crimson / Rose Pink
    LyricsPalette(
        top = Color(0xFF9E2A5B),
        mid = Color(0xFF561533),
        deep = Color(0xFF280918),
        bottom = Color(0xFF12040B),
        glow = Color(0xFFD8437E),
        accentText = Color(0xFFFFB3D1),
    ),
    // 5. Sunset / Warm Amber Copper
    LyricsPalette(
        top = Color(0xFF9A4B1A),
        mid = Color(0xFF54270C),
        deep = Color(0xFF261105),
        bottom = Color(0xFF120702),
        glow = Color(0xFFF59E0B),
        accentText = Color(0xFFFDE68A),
    ),
    // 6. Midnight / Arctic Cyan
    LyricsPalette(
        top = Color(0xFF16607A),
        mid = Color(0xFF0B3444),
        deep = Color(0xFF051921),
        bottom = Color(0xFF020B10),
        glow = Color(0xFF06B6D4),
        accentText = Color(0xFFA5F3FC),
    ),
    // 7. Ruby / Wine Red
    LyricsPalette(
        top = Color(0xFF8F2436),
        mid = Color(0xFF4D111C),
        deep = Color(0xFF24070D),
        bottom = Color(0xFF100306),
        glow = Color(0xFFE11D48),
        accentText = Color(0xFFFECDD3),
    ),
    // 8. Cobalt / Indigo Night
    LyricsPalette(
        top = Color(0xFF353899),
        mid = Color(0xFF1D1F57),
        deep = Color(0xFF0D0E2B),
        bottom = Color(0xFF060614),
        glow = Color(0xFF6366F1),
        accentText = Color(0xFFC7D2FE),
    ),
    // 9. Jade / Moss Green
    LyricsPalette(
        top = Color(0xFF2B6E3F),
        mid = Color(0xFF173D22),
        deep = Color(0xFF0A1D10),
        bottom = Color(0xFF040D07),
        glow = Color(0xFF22C55E),
        accentText = Color(0xFFBBF7D0),
    ),
    // 10. Magenta / Electric Plum
    LyricsPalette(
        top = Color(0xFF832B8C),
        mid = Color(0xFF46164D),
        deep = Color(0xFF210924),
        bottom = Color(0xFF0F0411),
        glow = Color(0xFFC026D3),
        accentText = Color(0xFFF5D0FE),
    ),
)

@Volatile
private var lastLyricsPaletteIndex: Int = -1

internal fun nextRandomLyricsPalette(): LyricsPalette {
    val size = LYRICS_PALETTES.size
    if (size <= 1) return LYRICS_PALETTES.first()
    val prev = lastLyricsPaletteIndex
    var next = Random.nextInt(size)
    if (next == prev) {
        next = (next + Random.nextInt(1, size)) % size
    }
    lastLyricsPaletteIndex = next
    return LYRICS_PALETTES[next]
}

@Composable
fun LyricsView(
    track: Track,
    modifier: Modifier = Modifier,
    onMinimize: (() -> Unit)? = null,
) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val strings = LocalStrings.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val artworkUrl = rememberTrackArtwork(track)
    val palette = remember(track.id) { nextRandomLyricsPalette() }
    val coverColor by rememberCoverColor(artworkUrl, palette.top)
    val topTint by animateColorAsState(lerp(palette.top, coverColor, 0.22f), tween(600), label = "lyrics_top")
    val midTint by animateColorAsState(palette.mid, tween(600), label = "lyrics_mid")
    val deepTint by animateColorAsState(palette.deep, tween(600), label = "lyrics_deep")
    val bottomTint by animateColorAsState(palette.bottom, tween(600), label = "lyrics_bottom")
    val glowTint by animateColorAsState(palette.glow, tween(600), label = "lyrics_glow")
    val accentTextTint by animateColorAsState(palette.accentText, tween(600), label = "lyrics_accent_text")

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
    val position = rememberSmoothPosition()
    val activeIndex by remember(lyrics?.lines, lyrics?.isSynced) {
        derivedStateOf {
            val l = lyrics ?: return@derivedStateOf -1
            if (l.isSynced) LrcParser.activeIndex(l.lines, position.value / 1000.0) else -1
        }
    }
    val hasNonLatin = remember(lyrics?.lines) {
        lyrics?.lines?.any { line -> line.text.any { ch -> ch.code > 0x024F } } == true
    }

    Box(
        modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        topTint,
                        midTint,
                        deepTint,
                        bottomTint,
                    ),
                ),
            )
            .drawWithContent {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowTint.copy(alpha = 0.34f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.45f, size.height * 0.36f),
                        radius = size.minDimension * 0.85f,
                    ),
                )
                drawContent()
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Top header bar (matching the purple fullscreen design)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onMinimize != null) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(onClick = onMinimize),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }

                Artwork(
                    url = artworkUrl,
                    size = 38.dp,
                    shape = RoundedCornerShape(8.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = track.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (lyrics != null) {
                    // Font size ("Aa") pill button
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable {
                                val order = listOf("sm", "md", "lg", "xl")
                                val next = order[(order.indexOf(settings.lyricsFontSize) + 1) % order.size]
                                scope.launch { graph.settings.update { it.copy(lyricsFontSize = next) } }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Aa",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }

                    if (hasNonLatin) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (settings.lyricsRomanization) Color.White.copy(alpha = 0.28f)
                                    else Color.White.copy(alpha = 0.14f),
                                )
                                .clickable {
                                    scope.launch { graph.settings.update { it.copy(lyricsRomanization = !it.lyricsRomanization) } }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Aa→ア",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Translate pill button
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (showTranslation) Color.White.copy(alpha = 0.28f)
                                else Color.White.copy(alpha = 0.14f),
                            )
                            .clickable {
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
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (translating) t("translating") else t("translate"),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Share circular button
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable {
                                val activeLine = lyrics.lines.getOrNull(activeIndex)?.text?.takeIf { it.isNotBlank() }
                                    ?: lyrics.lines.firstOrNull { !it.isInstrumental && it.text.isNotBlank() }?.text.orEmpty()
                                val shareText = if (activeLine.isNotBlank()) {
                                    "\"$activeLine\"\n— ${track.title} · ${track.artist}"
                                } else {
                                    "${track.title} · ${track.artist}"
                                }
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                runCatchingCancellable {
                                    context.startActivity(Intent.createChooser(sendIntent, track.title))
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }

            // Center lyrics area
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    res == null -> LoadingBox(Modifier.fillMaxSize())
                    lyrics == null -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (res.networkError) t("lyricsNetworkError") else t("noLyrics"),
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        TextButton(onClick = { reload++ }) {
                            Icon(Icons.Default.Refresh, null)
                            Text(t("retry"))
                        }
                    }
                    else -> LyricsLines(
                        lines = lyrics.lines,
                        synced = lyrics.isSynced,
                        translation = if (showTranslation) translation else null,
                        romanize = settings.lyricsRomanization,
                        karaoke = settings.lyricsKaraoke,
                        fontSize = settings.lyricsFontSize,
                        position = position,
                        active = activeIndex,
                        accentTextColor = accentTextTint,
                    )
                }
            }

            // Bottom transport controls (SeekBar + Previous, Play/Pause, Next)
            LyricsTransportBar(
                accentColor = c.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun LyricsTransportBar(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val position by graph.player.position.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = seeking ?: (position.toFloat() / duration).coerceIn(0f, 1f)

    Column(modifier) {
        SeekBar(
            fraction = fraction,
            onPreview = { seeking = it },
            onSeek = {
                graph.player.seekTo((it * duration).toLong())
                seeking = null
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                formatMs(seeking?.let { (it * duration).toLong() } ?: position),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatMs(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { graph.player.previous() },
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(24.dp))
            Box(
                Modifier
                    .size(60.dp)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = { graph.player.togglePlay() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            Modifier.size(26.dp),
                            color = Color.Black,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            IconButton(
                onClick = { graph.player.next() },
                enabled = state.hasNext || state.queue.isNotEmpty(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = null,
                    tint = if (state.hasNext || state.queue.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricsLines(
    lines: List<LyricLine>,
    synced: Boolean,
    translation: List<String>?,
    romanize: Boolean,
    karaoke: Boolean,
    fontSize: String,
    position: State<Long>,
    active: Int,
    accentTextColor: Color,
) {
    val graph = LocalGraph.current
    val listState = rememberLazyListState()
    val size = when (fontSize) {
        "sm" -> 19.sp
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
        listState.animateScrollToItem(active, -(viewport * 2 / 5))
    }
    val activeVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == active } } }

    val romanized = remember(lines, romanize) { if (romanize) lines.map { Romanizer.romanize(it.text) } else null }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 72.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.09f to Color.Black,
                            0.88f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(lines) { i, line ->
                val isActive = i == active
                val dist = if (synced && active >= 0) abs(i - active) else 0
                val targetAlpha = when {
                    !synced -> 0.92f
                    isActive -> 1f
                    dist == 1 -> 0.62f
                    dist == 2 -> 0.48f
                    dist == 3 -> 0.36f
                    dist == 4 -> 0.26f
                    else -> 0.18f
                }
                val blurRadius = when {
                    !synced || dist <= 1 -> 0.dp
                    dist == 2 -> 0.8.dp
                    dist == 3 -> 2.2.dp
                    dist == 4 -> 3.8.dp
                    else -> 5.2.dp
                }
                val color by animateColorAsState(
                    Color.White.copy(alpha = targetAlpha),
                    animationSpec = tween(250),
                    label = "line_color",
                )
                val lineFontSize = if (isActive && synced) size * 1.12f else size
                val nextTimeSec = lines.getOrNull(i + 1)?.timeSec ?: (line.timeSec + 4.0)

                Column(
                    Modifier
                        .fillMaxWidth()
                        .let { mod -> if (blurRadius > 0.dp) mod.blur(blurRadius) else mod }
                        .clickable(enabled = synced) {
                            lastUserScrollAt = 0L
                            graph.player.seekTo((line.timeSec * 1000).toLong())
                        }
                        .padding(vertical = 10.dp),
                ) {
                    if (isActive && synced && karaoke && !line.isInstrumental) {
                        val words = line.words?.takeIf { it.isNotEmpty() }
                            ?: remember(line.text, line.timeSec, nextTimeSec) {
                                interpolateWords(line.text, line.timeSec, nextTimeSec)
                            }
                        KaraokeLine(words, position, lineFontSize)
                    } else {
                        Text(
                            text = if (line.isInstrumental) LrcParser.INSTRUMENTAL else line.text,
                            color = color,
                            fontSize = lineFontSize,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                            lineHeight = lineFontSize * 1.28f,
                            style = if (isActive) {
                                TextStyle(
                                    shadow = Shadow(
                                        color = Color.White.copy(alpha = 0.35f),
                                        offset = Offset.Zero,
                                        blurRadius = 18f,
                                    ),
                                )
                            } else {
                                TextStyle.Default
                            },
                        )
                    }
                    romanized?.getOrNull(i)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                        Text(
                            it,
                            color = color.copy(alpha = color.alpha * 0.8f),
                            fontSize = size * 0.65f,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    translation?.getOrNull(i)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                        Text(
                            it,
                            color = accentTextColor.copy(alpha = if (isActive || !synced) 0.95f else targetAlpha * 0.85f),
                            fontSize = size * 0.68f,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        // Back to the line being sung after scrolling away from it
        if (synced && active >= 0 && lastUserScrollAt > 0L && !activeVisible && !dragging) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { lastUserScrollAt = 0L }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.MyLocation, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("lyricsBackToLine"), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Generates smooth word-by-word timestamps across [startSec, endSec] weighted by word length
 * when the LRC source only provides line-level timestamps.
 */
internal fun interpolateWords(text: String, startSec: Double, endSec: Double): List<LyricWord> {
    val tokens = Regex("""\S+\s*""").findAll(text).map { it.value }.toList()
    if (tokens.isEmpty()) return emptyList()
    val rawSpan = (endSec - startSec).coerceIn(1.2, 8.0)
    // Singing typically occupies the first ~85% of the interval before the next line starts
    val singSpan = (rawSpan * 0.85).coerceAtLeast(0.8)
    val weights = tokens.map { it.trim().length.coerceAtLeast(2).toDouble() }
    val totalWeight = weights.sum().coerceAtLeast(1.0)
    var accWeight = 0.0
    return tokens.mapIndexed { idx, token ->
        val wordStart = startSec + singSpan * (accWeight / totalWeight)
        accWeight += weights[idx]
        LyricWord(timeSec = wordStart, text = token)
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
                withStyle(
                    SpanStyle(
                        color = if (sung) Color.White else Color.White.copy(alpha = 0.42f),
                        shadow = if (sung) {
                            Shadow(
                                color = Color.White.copy(alpha = 0.38f),
                                offset = Offset.Zero,
                                blurRadius = 18f,
                            )
                        } else null,
                    ),
                ) {
                    append(w.text)
                }
            }
        },
        fontSize = size,
        fontWeight = FontWeight.ExtraBold,
        lineHeight = size * 1.28f,
    )
}
