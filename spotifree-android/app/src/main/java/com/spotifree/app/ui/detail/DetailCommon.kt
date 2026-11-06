package com.spotifree.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spotifree.app.ui.common.PlayFab
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.player.rememberCoverColor
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import androidx.compose.runtime.getValue

@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Header colour of a detail page: the cover's colour, or the fixed gradient of special lists. */
@Composable
fun rememberHeaderColor(coverUrl: String?, gradient: List<Color>? = null): Color {
    val c = LocalSpotifreeColors.current
    val cover by rememberCoverColor(coverUrl, c.elevated)
    val target = if (gradient != null && coverUrl == null) {
        val g = gradient.first()
        Color(g.red * 0.7f, g.green * 0.7f, g.blue * 0.7f)
    } else Color(minOf(1f, cover.red * 1.45f), minOf(1f, cover.green * 1.45f), minOf(1f, cover.blue * 1.45f)) // livelier than the player's
    return animateColorAsState(target, tween(500), label = "header").value
}

/**
 * Top bar drawn over the header: transparent at first (round back button over the cover), then
 * filled with the header colour and showing the title once the header scrolls away (Spotify).
 */
@Composable
fun CollapsingTopBar(title: String, color: Color, listState: LazyListState, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val progress by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / 520f).coerceIn(0f, 1f)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = progress)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f * (1f - progress))), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(progress))
        actions()
    }
}

/**
 * Header of playlists, albums and artists: gradient from the cover colour reaching behind the
 * status bar, big cover with a shadow and a large left-aligned title (Spotify / previous app).
 * [artistLayout] puts a round picture beside the name with an accent "ARTIST" label instead.
 */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String,
    coverUrl: String?,
    color: Color,
    coverShape: Shape = RoundedCornerShape(6.dp),
    gradient: List<Color>? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.LibraryMusic,
    label: String? = null,
    artistLayout: Boolean = false,
) {
    val c = LocalSpotifreeColors.current
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to color, 0.85f to c.background))
            .statusBarsPadding()
            .padding(top = 56.dp, bottom = 4.dp),
    ) {
        if (artistLayout) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(coverUrl, size = 120.dp, shape = CircleShape, placeholder = icon,
                    modifier = Modifier.shadow(16.dp, CircleShape).border(2.dp, Color.White.copy(alpha = 0.15f), CircleShape))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (label != null) Text(label.uppercase(), color = c.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp)
                    Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                if (gradient != null && coverUrl == null) {
                    Box(Modifier.size(220.dp).shadow(20.dp, coverShape).clip(coverShape).background(Brush.linearGradient(gradient)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, modifier = Modifier.size(84.dp), tint = Color.White)
                    }
                } else {
                    Artwork(coverUrl, size = 220.dp, shape = coverShape, placeholder = icon, modifier = Modifier.shadow(20.dp, coverShape))
                }
            }
            Spacer(Modifier.height(20.dp))
            if (label != null) Text(label.uppercase(), color = c.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

/** Action row: extra buttons on the left, shuffle and the big play button on the right. */
@Composable
fun PlayRow(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean, extra: @Composable () -> Unit) {
    val c = LocalSpotifreeColors.current
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        extra()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShuffle, enabled = enabled) { Icon(Icons.Default.Shuffle, t("shuffle"), tint = c.textSecondary, modifier = Modifier.size(28.dp)) }
        PlayFab(onPlay, size = 56.dp, enabled = enabled)
    }
}

@Composable
fun FilterField(value: String, onChange: (String) -> Unit) {
    val c = LocalSpotifreeColors.current
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(t("searchInPlaylist")) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, null) } },
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.elevated, unfocusedContainerColor = c.elevated,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

fun matches(q: String, title: String, artist: String, album: String): Boolean {
    if (q.isBlank()) return true
    val n = com.spotifree.app.data.remote.MusicRepository.normalize(q)
    return com.spotifree.app.data.remote.MusicRepository.normalize("$title $artist $album").contains(n)
}
