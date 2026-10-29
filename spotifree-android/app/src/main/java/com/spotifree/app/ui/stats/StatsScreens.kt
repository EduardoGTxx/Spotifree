package com.spotifree.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifree.app.AppGraph
import com.spotifree.app.data.db.ArtistPlayCount
import com.spotifree.app.data.db.DayCount
import com.spotifree.app.data.db.HourCount
import com.spotifree.app.data.db.TrackPlayCount
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.EmptyState
import com.spotifree.app.ui.common.LoadingBox
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.detail.DetailTopBar
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import java.time.LocalDate
import java.time.ZoneId

data class StatsData(
    val minutes: Long,
    val plays: Int,
    val tracks: List<TrackPlayCount>,
    val artists: List<ArtistPlayCount>,
    val days: List<DayCount>,
    val hours: List<HourCount>,
)

suspend fun loadStats(graph: AppGraph, from: Long, to: Long, limit: Int = 10): StatsData {
    val h = graph.db.history()
    return StatsData(
        minutes = h.totalSeconds(from, to) / 60,
        plays = h.totalPlays(from, to),
        tracks = h.topTracks(from, to, limit),
        artists = h.topArtists(from, to, limit),
        days = h.byDay(from, to),
        hours = h.byHour(from, to),
    )
}

private fun startOfDaysAgo(days: Long): Long =
    LocalDate.now().minusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val c = LocalSpotifreeColors.current
    var period by remember { mutableStateOf("30") }
    val data by produceState<StatsData?>(null, period) {
        value = null
        val from = when (period) {
            "7" -> startOfDaysAgo(7)
            "30" -> startOfDaysAgo(30)
            "365" -> startOfDaysAgo(365)
            else -> 0L
        }
        value = loadStats(graph, from, Long.MAX_VALUE)
    }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(t("statsTitle"), onBack)
        val periods = listOf("7" to t("last7Days"), "30" to t("last30Days"), "365" to t("lastYear"), "all" to t("allTime"))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(periods) { (k, label) ->
                FilterChip(selected = period == k, onClick = { period = k }, label = { Text(label) }, shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = Color.Black))
            }
        }
        val d = data
        when {
            d == null -> LoadingBox()
            d.plays == 0 -> EmptyState(Icons.Default.BarChart, t("noStatsYet"), t("noStatsYetSub"))
            else -> StatsBody(d)
        }
    }
}

@Composable
private fun StatsBody(d: StatsData) {
    val c = LocalSpotifreeColors.current
    val actions = LocalActions.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(t("minutesListened"), "%,d".format(d.minutes), Modifier.weight(1f))
                StatCard(t("playsCount"), "%,d".format(d.plays), Modifier.weight(1f))
            }
        }
        if (d.hours.isNotEmpty()) item {
            SectionTitle(t("byHour"))
            HourChart(d.hours)
        }
        if (d.days.size > 1) item {
            SectionTitle(t("byDay"))
            DayChart(d.days.takeLast(60))
        }
        item { SectionTitle(t("topSongs")) }
        itemsIndexed(d.tracks) { i, tr ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", modifier = Modifier.width(28.dp), color = c.textSecondary)
                Artwork(tr.coverUrl, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Text(t("playsN", tr.plays), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { SectionTitle(t("topArtists")) }
        itemsIndexed(d.artists) { i, a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", modifier = Modifier.width(28.dp), color = c.textSecondary)
                Text(a.artist, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t("minutesN", a.seconds / 60), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    val c = LocalSpotifreeColors.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.elevated).padding(16.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = c.accent)
        Text(label, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HourChart(hours: List<HourCount>) {
    val c = LocalSpotifreeColors.current
    val byHour = IntArray(24).also { arr -> hours.forEach { if (it.hour in 0..23) arr[it.hour] = it.plays } }
    val max = (byHour.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(Modifier.padding(horizontal = 16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width / 24f
            byHour.forEachIndexed { i, v ->
                val h = size.height * v / max
                drawRoundRect(c.accent.copy(alpha = if (v == max) 1f else 0.6f), Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(4f, 4f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0h", "6h", "12h", "18h", "23h").forEach { Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.textSecondary) }
        }
    }
}

@Composable
private fun DayChart(days: List<DayCount>) {
    val c = LocalSpotifreeColors.current
    val max = (days.maxOfOrNull { it.seconds } ?: 1L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(100.dp).padding(horizontal = 16.dp)) {
        val w = size.width / days.size
        days.forEachIndexed { i, d ->
            val h = size.height * d.seconds / max
            drawRect(c.accent.copy(alpha = 0.75f), Offset(i * w, size.height - h), Size((w * 0.8f).coerceAtLeast(1f), h))
        }
    }
}

// ------------------------------------------------------------------ Rewind (year in review)

@Composable
fun RewindScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val strings = LocalStrings.current
    val c = LocalSpotifreeColors.current
    val first by produceState<Long?>(null) { value = graph.db.history().firstPlayedAt() ?: 0L }
    val years = remember(first) {
        val now = LocalDate.now().year
        val start = first?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year } ?: now
        (now downTo start).toList()
    }
    var year by remember { mutableStateOf(LocalDate.now().year) }
    val data by produceState<StatsData?>(null, year) {
        value = null
        val zone = ZoneId.systemDefault()
        val from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        value = loadStats(graph, from, to, 5)
    }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(t("rewindTitle"), onBack)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years) { y ->
                FilterChip(selected = year == y, onClick = { year = y }, label = { Text("$y") }, shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = Color.Black))
            }
        }
        val d = data
        when {
            d == null -> LoadingBox()
            d.plays == 0 -> EmptyState(Icons.Default.BarChart, t("noStatsYet"), t("noStatsYetSub"))
            else -> {
                val topHour = d.hours.maxByOrNull { it.plays }?.hour
                val topDay = d.days.maxByOrNull { it.seconds }
                val slides = listOf<@Composable () -> Unit>(
                    { Slide(listOf(Color(0xFF7B1FA2), Color(0xFF1A237E)), t("rewindYourYear", year), "%,d".format(d.minutes), t("rewindMinutes")) },
                    {
                        val tt = d.tracks.firstOrNull()
                        Slide(listOf(Color(0xFFD81B60), Color(0xFF4A148C)), t("rewindTopSong"), tt?.title ?: "-", tt?.let { strings.f("rewindPlays", it.artist, it.plays) } ?: "", tt?.coverUrl)
                    },
                    {
                        val ta = d.artists.firstOrNull()
                        Slide(listOf(Color(0xFF00897B), Color(0xFF004D40)), t("rewindTopArtist"), ta?.artist ?: "-", ta?.let { strings.f("minutesN", it.seconds / 60) } ?: "")
                    },
                    {
                        Slide(listOf(Color(0xFFF57C00), Color(0xFFBF360C)), t("rewindHabits"),
                            topHour?.let { "%02d:00".format(it) } ?: "-", listOfNotNull(t("rewindFavHour"), topDay?.let { strings.f("rewindTopDay", it.day) }).joinToString("\n"))
                    },
                    { TopList(d) },
                )
                val pager = rememberPagerState { slides.size }
                HorizontalPager(pager, modifier = Modifier.weight(1f).padding(16.dp), pageSpacing = 12.dp) { page -> slides[page]() }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(slides.size) { i ->
                        Box(Modifier.padding(3.dp).size(8.dp).clip(CircleShape).background(if (pager.currentPage == i) Color.White else Color.White.copy(alpha = 0.3f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Slide(colors: List<Color>, title: String, big: String, sub: String, image: String? = null) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(colors)).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (image != null) {
            Artwork(image, size = 180.dp, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(big, fontSize = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun TopList(d: StatsData) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF1565C0), Color(0xFF0D0D0D)))).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(t("topSongs"), style = MaterialTheme.typography.titleLarge)
        d.tracks.forEachIndexed { i, tr -> Text("${i + 1}. ${tr.title} — ${tr.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(t("topArtists"), style = MaterialTheme.typography.titleLarge)
        d.artists.forEachIndexed { i, a -> Text("${i + 1}. ${a.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}
