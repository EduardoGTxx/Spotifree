package com.spotifree.app.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
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
import com.spotifree.app.ui.theme.onColor
import androidx.compose.runtime.getValue

@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Cover + title + subtitle on a gradient made from the cover's colour. */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String,
    coverUrl: String?,
    coverShape: Shape = RoundedCornerShape(6.dp),
    gradient: List<Color>? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.LibraryMusic,
) {
    val c = LocalSpotifreeColors.current
    val cover by rememberCoverColor(coverUrl, c.elevated)
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(gradient?.first() ?: cover, c.background))).padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (gradient != null && coverUrl == null) {
            Box(Modifier.size(200.dp).clip(coverShape).background(Brush.linearGradient(gradient)), contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(72.dp), tint = Color.White)
            }
        } else {
            Artwork(coverUrl, size = 200.dp, shape = coverShape, placeholder = icon)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp))
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
    }
}

/** Action row: extra buttons on the left, shuffle and the big play button on the right. */
@Composable
fun PlayRow(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean, extra: @Composable () -> Unit) {
    val c = LocalSpotifreeColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        extra()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShuffle, enabled = enabled) { Icon(Icons.Default.Shuffle, t("shuffle"), tint = c.textSecondary) }
        Box(Modifier.size(56.dp).clip(CircleShape).background(if (enabled) c.accent else c.elevated), contentAlignment = Alignment.Center) {
            IconButton(onClick = onPlay, enabled = enabled) { Icon(Icons.Default.PlayArrow, t("playAll"), tint = onColor(c.accent), modifier = Modifier.size(32.dp)) }
        }
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
