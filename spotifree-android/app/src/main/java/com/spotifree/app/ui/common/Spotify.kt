package com.spotifree.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import com.spotifree.app.ui.theme.onColor

/*
 * Building blocks shared by the screens: the look of the previous (web) app — pills, accent play
 * buttons, atmospheric gradients, coloured shortcut tiles — on top of the native components.
 */

/** Rounded filter pill: translucent white, accent when selected (previous app's chips). */
@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = LocalSpotifreeColors.current
    val bg = if (selected) c.accent else c.chip
    val fg = if (selected) onColor(c.accent) else Color.White
    Row(
        modifier.height(34.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Outlined pill used for secondary actions (Follow, Radio, Paste link…). */
@Composable
fun OutlinePill(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    borderColor: Color = Color.White.copy(alpha = 0.3f),
    onClick: () -> Unit,
) {
    Row(
        modifier.height(36.dp).clip(CircleShape).border(1.dp, borderColor, CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Horizontal row of [Pill]s. */
@Composable
fun <T> PillRow(options: List<Pair<T, String>>, selected: T, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), onSelect: (T) -> Unit) {
    LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (value, label) -> Pill(label, selected = value == selected) { onSelect(value) } }
    }
}

/** Big round accent play button. */
@Composable
fun PlayFab(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp, enabled: Boolean = true, icon: ImageVector = Icons.Default.PlayArrow) {
    val c = LocalSpotifreeColors.current
    Box(
        modifier.size(size).shadow(if (enabled) 8.dp else 0.dp, CircleShape).clip(CircleShape)
            .background(if (enabled) c.accent else c.elevated)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) onColor(c.accent) else c.textSecondary, modifier = Modifier.size(size * 0.55f))
    }
}

/** The tinted glow behind the top of a screen (previous app's "atmospheric" header). */
@Composable
fun TopGlow(color: Color, height: Dp = 280.dp, modifier: Modifier = Modifier) {
    val c = LocalSpotifreeColors.current
    Box(
        modifier.fillMaxWidth().height(height)
            .background(Brush.verticalGradient(0f to color.copy(alpha = 0.45f), 0.75f to c.background.copy(alpha = 0.9f), 1f to c.background)),
    )
}

/** A colourful gradient square with an icon: covers for shortcuts that have no picture. */
@Composable
fun GradientIcon(colors: List<Color>, icon: ImageVector, size: Dp, modifier: Modifier = Modifier, iconScale: Float = 0.45f) {
    Box(modifier.size(size).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * iconScale))
    }
}

/** "Browse all" category card: bold title and a tilted icon tile in the corner. */
@Composable
fun CategoryCard(title: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(100.dp).clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(red = color.red * 0.8f, green = color.green * 0.8f, blue = color.blue * 0.8f))))
            .clickable(onClick = onClick),
    ) {
        Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp).padding(end = 40.dp))
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 6.dp).rotate(25f).size(62.dp)
                .shadow(6.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
    }
}

/** Text link on the right of a section title ("Search more", "Explore"). */
@Composable
fun SectionLink(text: String, onClick: () -> Unit) {
    val c = LocalSpotifreeColors.current
    Text(text, color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

/**
 * Spotify-style seek bar: a thin track that thickens while dragging, with a small white thumb.
 * [fraction] is 0..1; [onSeek] receives the final position when the finger lifts.
 */
@Composable
fun SeekBar(fraction: Float, onPreview: (Float?) -> Unit, onSeek: (Float) -> Unit, modifier: Modifier = Modifier, color: Color = Color.White) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val shown = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f); onPreview(dragFraction) },
                    onDragEnd = { dragging = false; onPreview(null); onSeek(dragFraction) },
                    onDragCancel = { dragging = false; onPreview(null) },
                    onHorizontalDrag = { ch, d ->
                        ch.consume()
                        dragFraction = (dragFraction + d / size.width).coerceIn(0f, 1f)
                        onPreview(dragFraction)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val y = size.height / 2
            val stroke = if (dragging) 6.dp.toPx() else 4.dp.toPx()
            drawLine(color.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val x = size.width * shown
            drawLine(color, Offset(0f, y), Offset(x, y), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(color, radius = if (dragging) 8.dp.toPx() else 6.dp.toPx(), center = Offset(x, y))
        }
    }
}

/** Call-to-action pill: filled accent (primary) or dark grey (secondary), as in the previous app's empty states. */
@Composable
fun ActionPill(text: String, icon: ImageVector? = null, primary: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalSpotifreeColors.current
    val bg = if (primary) c.accent else Color.White.copy(alpha = 0.12f)
    val fg = if (primary) onColor(c.accent) else Color.White
    Row(
        modifier.height(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
