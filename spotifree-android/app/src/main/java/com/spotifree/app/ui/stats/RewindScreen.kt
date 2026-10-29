package com.spotifree.app.ui.stats

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.Strings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.JakartaSans
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import com.spotifree.app.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/*
 * Rewind: the year in music as full-screen stories. Progress bars on top, auto-advance, tap the
 * right side for the next story and the left side for the previous one, hold to pause. The last
 * story is a summary card that can be shared or saved to the gallery.
 */

private const val STORY_MS = 7000

private enum class Story { INTRO, MINUTES, TOP_ARTIST, TOP_ARTISTS, TOP_SONG, TOP_SONGS, HABITS, SUMMARY }

private data class RewindData(
    val year: Int,
    val stats: StatsData,
    val topTracks: List<Track>,
    val artistImage: String?,
    val covers: Map<String, String?>,
)

private data class Palette(val top: Color, val bottom: Color, val shape: Color)

private val PALETTES = mapOf(
    Story.INTRO to Palette(Color(0xFF1B1464), Color(0xFF2979FF), Color(0xFFFF4FA0)),
    Story.MINUTES to Palette(Color(0xFFC2185B), Color(0xFFFF6F3C), Color(0xFFFFD166)),
    Story.TOP_ARTIST to Palette(Color(0xFF0B2545), Color(0xFF00B3A4), Color(0xFFB8F2E6)),
    Story.TOP_ARTISTS to Palette(Color(0xFF0F2A1D), Color(0xFF2E7D32), Color(0xFFC6F432)),
    Story.TOP_SONG to Palette(Color(0xFF120024), Color(0xFF7B1FA2), Color(0xFFFF8A65)),
    Story.TOP_SONGS to Palette(Color(0xFF2B0A3D), Color(0xFFFF5E5B), Color(0xFFFFE066)),
    Story.HABITS to Palette(Color(0xFFE65100), Color(0xFFFFB300), Color(0xFF1B1464)),
    Story.SUMMARY to Palette(Color(0xFF050505), Color(0xFF151515), Color(0xFF2979FF)),
)

@Composable
fun RewindScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val first by produceState<Long?>(null) { value = graph.db.history().firstPlayedAt() ?: 0L }
    val years = remember(first) {
        val now = LocalDate.now().year
        val start = first?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year } ?: now
        (now downTo start).toList()
    }
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    val data by produceState<RewindData?>(null, year) {
        value = null
        val zone = ZoneId.systemDefault()
        val from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val raw = loadStats(graph, from, to, 5)
        val totalSec = runCatchingCancellable { graph.db.history().totalSeconds(from, to) }.getOrDefault(raw.minutes * 60)
        val stats = raw.copy(minutes = (totalSec + 59) / 60)
        val tracks = runCatchingCancellable { graph.library.tracksByIds(stats.tracks.map { it.trackId }) }.getOrDefault(emptyList())
        // Covers of each artist's most played song, used as their picture
        val covers = runCatchingCancellable { graph.db.history().topTracks(from, to, 200) }.getOrDefault(emptyList())
            .groupBy { it.artist.lowercase() }.mapValues { (_, l) -> l.firstNotNullOfOrNull { it.coverUrl } }
        value = RewindData(year, stats, tracks, null, covers)
        // Real picture of the #1 artist when online (best effort)
        val top = stats.artists.firstOrNull()?.artist
        if (top != null && !graph.settings.current.offlineMode) {
            val img = withTimeoutOrNull(6000) { runCatchingCancellable { graph.music.artist(top).imageUrl }.getOrNull() }
            if (img != null) value = value?.copy(artistImage = img)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val d = data
        when {
            d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            d.stats.plays == 0 -> EmptyRewind(year, years, onYear = { year = it }, onClose = onBack)
            else -> Stories(d, years, onYear = { year = it }, onClose = onBack)
        }
    }
}

@Composable
private fun Stories(d: RewindData, years: List<Int>, onYear: (Int) -> Unit, onClose: () -> Unit) {
    val stories = remember(d) {
        buildList {
            add(Story.INTRO)
            add(Story.MINUTES)
            if (d.stats.artists.isNotEmpty()) add(Story.TOP_ARTIST)
            if (d.stats.artists.size > 1) add(Story.TOP_ARTISTS)
            if (d.stats.tracks.isNotEmpty()) add(Story.TOP_SONG)
            if (d.stats.tracks.size > 1) add(Story.TOP_SONGS)
            if (d.stats.hours.isNotEmpty()) add(Story.HABITS)
            add(Story.SUMMARY)
        }
    }
    var index by remember(d.year) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val last = stories.lastIndex

    LaunchedEffect(index) { progress.snapTo(0f) }
    LaunchedEffect(index, paused) {
        if (paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * STORY_MS).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (index < last) index++
    }

    Box(Modifier.fillMaxSize()) {
        // Gesture layer underneath: plain texts let taps through, buttons keep theirs
        Box(
            Modifier.fillMaxSize().pointerInput(last) {
                detectTapGestures(
                    onPress = {
                        paused = true
                        tryAwaitRelease()
                        paused = false
                    },
                    onTap = { o ->
                        if (o.x < size.width * 0.3f) {
                            if (index > 0) index--
                        } else if (index < last) index++
                    },
                )
            },
        )
        AnimatedContent(
            targetState = index,
            transitionSpec = { (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 1.04f)) togetherWith fadeOut(tween(250)) },
            label = "story",
        ) { i ->
            val story = stories[i]
            StoryBackground(PALETTES.getValue(story), story) {
                when (story) {
                    Story.INTRO -> IntroStory(d.year, years, onYear)
                    Story.MINUTES -> MinutesStory(d)
                    Story.TOP_ARTIST -> TopArtistStory(d)
                    Story.TOP_ARTISTS -> TopArtistsStory(d)
                    Story.TOP_SONG -> TopSongStory(d)
                    Story.TOP_SONGS -> TopSongsStory(d)
                    Story.HABITS -> HabitsStory(d)
                    Story.SUMMARY -> SummaryStory(d, onReplay = { index = 0 })
                }
            }
        }
        // Progress bars + close
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stories.indices.forEach { i ->
                    val fill = when {
                        i < index -> 1f
                        i == index -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("rewindTitle") + " " + d.year, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (paused) Icon(Icons.Default.Pause, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, t("close"), tint = Color.White) }
            }
        }
    }
}

// ------------------------------------------------------------------ background

/** Gradient with big, slowly moving shapes (circles, rings or a starburst depending on the story). */
@Composable
private fun StoryBackground(p: Palette, story: Story, content: @Composable BoxScope.() -> Unit) {
    val infinite = rememberInfiniteTransition(label = "bg")
    val t by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(p.top, p.bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val a = t * 2f * Math.PI.toFloat()
            val w = size.width
            val h = size.height
            when (story.ordinal % 3) {
                0 -> { // floating circles
                    drawCircle(p.shape.copy(alpha = 0.55f), radius = w * 0.42f, center = Offset(w * 0.85f + cos(a) * 30f, h * 0.18f + sin(a) * 40f))
                    drawCircle(p.top.copy(alpha = 0.6f), radius = w * 0.30f, center = Offset(w * 0.05f + sin(a) * 40f, h * 0.82f + cos(a) * 30f))
                    drawCircle(Color.White.copy(alpha = 0.08f), radius = w * 0.6f, center = Offset(w * 0.5f, h * 1.05f))
                }
                1 -> { // concentric rings
                    for (i in 0 until 6) {
                        drawCircle(p.shape.copy(alpha = 0.5f - i * 0.07f), radius = w * (0.18f + i * 0.14f) + sin(a + i) * 10f,
                            center = Offset(w * 0.9f, h * 0.12f), style = Stroke(width = 18f))
                    }
                    drawCircle(p.shape.copy(alpha = 0.35f), radius = w * 0.22f, center = Offset(w * 0.1f, h * 0.9f + sin(a) * 20f))
                }
                else -> { // starburst
                    val c = Offset(w * 0.15f, h * 0.15f)
                    for (i in 0 until 12) {
                        val ang = a + i * (Math.PI.toFloat() / 6f)
                        drawLine(p.shape.copy(alpha = 0.35f), c, Offset(c.x + cos(ang) * w * 1.2f, c.y + sin(ang) * w * 1.2f), strokeWidth = 34f)
                    }
                    drawCircle(p.shape, radius = w * 0.12f, center = c)
                }
            }
        }
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 64.dp, bottom = 24.dp), content = content)
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun Big(text: String, size: TextUnit = 64.sp, color: Color = Color.White, align: TextAlign = TextAlign.Start, maxLines: Int = 3) {
    Text(text, color = color, fontSize = size, lineHeight = size * 1.02f, fontWeight = FontWeight.ExtraBold, fontFamily = JakartaSans,
        letterSpacing = (-0.04).em(), textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

@Composable
private fun Label(text: String, color: Color = Color.White) {
    Text(text, color = color, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
}

/** Value that appears after [delayMs] with a short rise. */
@Composable
private fun Reveal(delayMs: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        anim.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer { alpha = anim.value; translationY = (1f - anim.value) * 60f }) { content() }
}

/** Number that counts up from zero. */
@Composable
private fun CountUp(target: Long, delayMs: Int = 400): String {
    val anim = remember(target) { Animatable(0f) }
    LaunchedEffect(target) {
        delay(delayMs.toLong())
        anim.animateTo(target.toFloat(), tween(1800, easing = FastOutSlowInEasing))
    }
    return "%,d".format(anim.value.toLong())
}

private fun listenerType(hour: Int?, s: Strings): Pair<String, String> = when (hour) {
    null -> s["rewindTypeAllDay"] to s["rewindTypeAllDayDesc"]
    in 5..11 -> s["rewindTypeMorning"] to s["rewindTypeMorningDesc"]
    in 12..17 -> s["rewindTypeAfternoon"] to s["rewindTypeAfternoonDesc"]
    in 18..22 -> s["rewindTypeEvening"] to s["rewindTypeEveningDesc"]
    else -> s["rewindTypeNight"] to s["rewindTypeNightDesc"]
}

// ------------------------------------------------------------------ stories

@Composable
private fun BoxScope.IntroStory(year: Int, years: List<Int>, onYear: (Int) -> Unit) {
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 28.dp)) {
        Reveal(100) { Label(t("rewindIntroHey")) }
        Spacer(Modifier.height(12.dp))
        Reveal(500) { Big(t("rewindIntroTitle", year), 56.sp) }
        Spacer(Modifier.height(20.dp))
        Reveal(1100) { Label(t("rewindIntroSub"), Color.White.copy(alpha = 0.85f)) }
    }
    if (years.size > 1) {
        LazyRow(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(years) { y ->
                val sel = y == year
                Text("$y", color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(CircleShape).background(if (sel) Color.White else Color.White.copy(alpha = 0.18f))
                        .clickable { onYear(y) }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun BoxScope.MinutesStory(d: RewindData) {
    val strings = LocalStrings.current
    val minutes = d.stats.minutes
    val compare = when {
        minutes >= 1440 -> strings.f("rewindDaysEquivalent", "%.1f".format(minutes / 1440.0))
        minutes >= 60 -> strings.f("rewindHoursEquivalent", minutes / 60)
        else -> strings["rewindJustStarted"]
    }
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 28.dp)) {
        Reveal(100) { Label(t("rewindMinutesIntro")) }
        Spacer(Modifier.height(8.dp))
        Big(CountUp(minutes), 84.sp, maxLines = 1)
        Reveal(300) { Big(if (minutes == 1L) t("rewindMinutesUnitOne") else t("rewindMinutesUnit"), 40.sp) }
        Spacer(Modifier.height(24.dp))
        Reveal(2200) { Label(compare, Color.White.copy(alpha = 0.9f)) }
        Spacer(Modifier.height(8.dp))
        Reveal(2700) {
            Label(if (d.stats.plays == 1) strings["rewindPlaysTotalOne"] else strings.f("rewindPlaysTotal", "%,d".format(d.stats.plays)), Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun BoxScope.TopArtistStory(d: RewindData) {
    val a = d.stats.artists.first()
    val img = d.artistImage ?: d.covers[a.artist.lowercase()]
    Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Reveal(100) { Label(t("rewindTopArtistIntro")) }
        Spacer(Modifier.height(24.dp))
        Reveal(500) {
            Artwork(img, size = 230.dp, shape = CircleShape, placeholder = Icons.Default.Person,
                modifier = Modifier.shadow(24.dp, CircleShape))
        }
        Spacer(Modifier.height(24.dp))
        Reveal(1200) { Big(a.artist, 46.sp, align = TextAlign.Center, maxLines = 2) }
        Spacer(Modifier.height(12.dp))
        Reveal(1800) {
            val min = "%,d".format((a.seconds + 59) / 60)
            Label(if (a.plays == 1) t("rewindArtistMinutesOne", min) else t("rewindArtistMinutes", min, a.plays), Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun BoxScope.TopArtistsStory(d: RewindData) {
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 24.dp)) {
        Reveal(100) { Big(t("rewindTopArtistsTitle"), 40.sp) }
        Spacer(Modifier.height(24.dp))
        d.stats.artists.take(5).forEachIndexed { i, a ->
            Reveal(500 + i * 250) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = PALETTES.getValue(Story.TOP_ARTISTS).shape, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(44.dp))
                    Artwork(d.covers[a.artist.lowercase()], size = 58.dp, shape = CircleShape)
                    Spacer(Modifier.width(14.dp))
                    Text(a.artist, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.TopSongStory(d: RewindData) {
    val s = d.stats.tracks.first()
    val infinite = rememberInfiniteTransition(label = "spin")
    val rot by infinite.animateFloat(-4f, 4f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rot")
    Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Reveal(100) { Label(t("rewindTopSongIntro")) }
        Spacer(Modifier.height(24.dp))
        Reveal(500) {
            Artwork(s.coverUrl, size = 250.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.rotate(rot).shadow(28.dp, RoundedCornerShape(10.dp)))
        }
        Spacer(Modifier.height(24.dp))
        Reveal(1200) { Big(s.title, 38.sp, align = TextAlign.Center, maxLines = 2) }
        Reveal(1500) { Label(s.artist, Color.White.copy(alpha = 0.85f)) }
        Spacer(Modifier.height(12.dp))
        Reveal(2000) {
            Label(if (s.plays == 1) t("rewindSongPlaysOne") else t("rewindSongPlays", s.plays), PALETTES.getValue(Story.TOP_SONG).shape)
        }
    }
}

@Composable
private fun BoxScope.TopSongsStory(d: RewindData) {
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 24.dp)) {
        Reveal(100) { Big(t("rewindTopSongsTitle"), 40.sp) }
        Spacer(Modifier.height(24.dp))
        d.stats.tracks.take(5).forEachIndexed { i, s ->
            Reveal(500 + i * 250) {
                Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = PALETTES.getValue(Story.TOP_SONGS).shape, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(40.dp))
                    Artwork(s.coverUrl, size = 58.dp, shape = RoundedCornerShape(6.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(s.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.HabitsStory(d: RewindData) {
    val strings = LocalStrings.current
    val hour = d.stats.hours.maxByOrNull { it.plays }?.hour
    val (type, desc) = listenerType(hour, strings)
    val topDay = d.stats.days.maxByOrNull { it.seconds }
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 28.dp)) {
        Reveal(100) { Label(t("rewindTypeIntro"), Color(0xFF1B1464)) }
        Spacer(Modifier.height(12.dp))
        Reveal(600) { Big(type, 52.sp, color = Color(0xFF1B1464)) }
        Spacer(Modifier.height(16.dp))
        Reveal(1300) { Label(desc, Color(0xFF1B1464).copy(alpha = 0.85f)) }
        Spacer(Modifier.height(28.dp))
        if (hour != null) Reveal(2000) { Label(strings.f("rewindFavHourValue", "%02d:00".format(hour)), Color(0xFF1B1464)) }
        if (topDay != null) Reveal(2500) {
            Label(strings.f("rewindTopDay", prettyDay(topDay.day, strings.lang)), Color(0xFF1B1464))
        }
    }
}

private fun prettyDay(iso: String, lang: String): String = runCatchingCancellable {
    val date = LocalDate.parse(iso)
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.forLanguageTag(lang))
    date.format(fmt)
}.getOrDefault(iso)

@Composable
private fun BoxScope.SummaryStory(d: RewindData, onReplay: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val c = LocalSpotifreeColors.current
    val hour = d.stats.hours.maxByOrNull { it.plays }?.hour
    val (type, _) = listenerType(hour, strings)

    Column(Modifier.align(Alignment.Center).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The card that gets shared
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(c.accent, Color(0xFF1B1464), Color(0xFF0A0A0A))))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(d.artistImage ?: d.stats.artists.firstOrNull()?.let { d.covers[it.artist.lowercase()] }, size = 92.dp, shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(t("rewindMyRewind", d.year), color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Big(type, 26.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f)) {
                    SummaryHeader(t("topArtists"))
                    d.stats.artists.take(5).forEachIndexed { i, a -> SummaryLine(i + 1, a.artist) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    SummaryHeader(t("topSongs"))
                    d.stats.tracks.take(5).forEachIndexed { i, s -> SummaryLine(i + 1, s.title) }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    SummaryHeader(t("minutesListened"))
                    Text("%,d".format(d.stats.minutes), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("Spotifree", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StoryButton(t("share"), Icons.Default.Share, primary = true) {
                scope.launch {
                    runCatchingCancellable {
                        val bmp = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                        val uri = saveToGallery(context, bmp, "Spotifree_Rewind_${d.year}_${System.currentTimeMillis() / 1000}.png")
                        if (uri != null) {
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                                    .putExtra(Intent.EXTRA_TEXT, strings.f("rewindShareText", d.year))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                null,
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }.onFailure { graph.messages.post(strings["rewindShareFailed"], isError = true) }
                }
            }
            if (d.topTracks.isNotEmpty()) StoryButton(t("rewindPlayTop"), Icons.Default.PlayArrow) { actions.play(d.topTracks) }
            StoryButton(null, Icons.Default.Replay, onClick = onReplay)
        }
        Spacer(Modifier.height(8.dp))
        Text(t("rewindSavedHint"), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SummaryHeader(text: String) {
    Text(text.uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SummaryLine(n: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$n", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StoryButton(text: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(46.dp).clip(CircleShape).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick).padding(horizontal = if (text == null) 13.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        if (text != null) {
            Spacer(Modifier.width(8.dp))
            Text(text, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
    }
}

/** Saves the card to Pictures/Spotifree (no permission needed on Android 10+) and returns its uri. */
private suspend fun saveToGallery(context: Context, bmp: Bitmap, name: String): Uri? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Spotifree")
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
    resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    uri
}

// ------------------------------------------------------------------ empty

@Composable
private fun EmptyRewind(year: Int, years: List<Int>, onYear: (Int) -> Unit, onClose: () -> Unit) {
    StoryBackground(PALETTES.getValue(Story.INTRO), Story.INTRO) {
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 28.dp)) {
            Big(t("rewindEmptyTitle", year), 44.sp)
            Spacer(Modifier.height(16.dp))
            Label(t("rewindEmptySub"), Color.White.copy(alpha = 0.85f))
        }
        if (years.size > 1) LazyRow(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years) { y ->
                Text("$y", color = if (y == year) Color.Black else Color.White, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(CircleShape).background(if (y == year) Color.White else Color.White.copy(alpha = 0.18f))
                        .clickable { onYear(y) }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Default.Close, null, tint = Color.White) }
    }
}
