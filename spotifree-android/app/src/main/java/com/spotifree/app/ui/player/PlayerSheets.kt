package com.spotifree.app.ui.player

import com.spotifree.app.util.runCatchingCancellable

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.spotifree.app.data.model.Track
import com.spotifree.app.playback.AudioEffects
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.MenuItem
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ------------------------------------------------------------------ queue

@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val c = LocalSpotifreeColors.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val rowPx = with(density) { 64.dp.toPx() }

    // Local copy so drags feel immediate; refreshed from the player when not dragging.
    val upcoming = remember { mutableStateListOf<Pair<Int, Track>>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.queue, state.queueIndex) {
        if (dragging >= 0) return@LaunchedEffect
        upcoming.clear()
        state.queue.forEachIndexed { i, t -> if (i > state.queueIndex) upcoming += i to t }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.navigationBarsPadding()) {
            state.current?.let { cur ->
                Text(t("nowPlaying"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(cur.artworkUrl, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur.title, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(cur.artist, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("nextInQueue"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (upcoming.isNotEmpty()) TextButton(onClick = { graph.player.clearUpcoming() }) { Text(t("clearQueue")) }
            }
            if (upcoming.isEmpty()) {
                Text(t("queueEmpty"), color = c.textSecondary, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                itemsIndexed(upcoming, key = { _, it -> "${it.first}-${it.second.id}" }) { pos, (absIndex, track) ->
                    val isDragged = dragging == pos
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) }
                            .background(if (isDragged) c.surface else Color.Transparent)
                            .clickable { graph.player.skipToQueueIndex(absIndex) }
                            .padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(track.artworkUrl, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { graph.player.removeQueueItem(absIndex) }) { Icon(Icons.Default.Close, null, tint = c.textSecondary) }
                        Icon(
                            Icons.Default.DragHandle, null, tint = c.textSecondary,
                            modifier = Modifier
                                .padding(12.dp)
                                .pointerInput(upcoming.size) {
                                    detectVerticalDragGestures(
                                        onDragStart = { dragging = upcoming.indexOfFirst { it.first == absIndex }; dragOffset = 0f },
                                        onDragEnd = {
                                            dragging = -1
                                            dragOffset = 0f
                                        },
                                        onDragCancel = { dragging = -1; dragOffset = 0f },
                                        onVerticalDrag = { _, d ->
                                            val from = dragging
                                            if (from < 0) return@detectVerticalDragGestures
                                            dragOffset += d
                                            val steps = (dragOffset / rowPx).toInt()
                                            if (steps != 0) {
                                                val to = (from + steps).coerceIn(0, upcoming.size - 1)
                                                if (to != from) {
                                                    val fromAbs = upcoming[from].first
                                                    val toAbs = upcoming[to].first
                                                    val item = upcoming.removeAt(from)
                                                    upcoming.add(to, item)
                                                    // keep absolute indices consistent with the player's order
                                                    val base = state.queueIndex + 1
                                                    for (k in upcoming.indices) upcoming[k] = (base + k) to upcoming[k].second
                                                    graph.player.moveQueueItem(fromAbs, toAbs)
                                                    dragging = to
                                                    dragOffset -= (to - from) * rowPx
                                                }
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sleep timer

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val c = LocalSpotifreeColors.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.sleepEndsAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.navigationBarsPadding()) {
            Text(t("sleepTimer"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            when (state.sleepMode) {
                "timed" -> {
                    val left = ((state.sleepEndsAt - now) / 1000).coerceAtLeast(0)
                    Text(t("sleepRemaining", "%d:%02d".format(left / 60, left % 60)), color = c.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                "end_of_song" -> Text(t("sleepEndOfSongActive"), color = c.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                MenuItem(Icons.Default.Timer, t("minutesN", m)) {
                    graph.player.setSleepTimer(m); onDismiss()
                }
            }
            MenuItem(Icons.Default.Bedtime, t("sleepEndOfSong")) { graph.player.setSleepEndOfSong(); onDismiss() }
            if (state.sleepMode.isNotEmpty()) {
                MenuItem(Icons.Default.Close, t("sleepCancel"), tint = MaterialTheme.colorScheme.error) { graph.player.setSleepTimer(0); onDismiss() }
            }
        }
    }
}


// ------------------------------------------------------------------ effects / EQ

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffectsSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    fun update(f: (com.spotifree.app.data.prefs.AppSettings) -> com.spotifree.app.data.prefs.AppSettings) = scope.launch { graph.settings.update(f) }
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = Color.Black)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(t("audioEffects"), style = MaterialTheme.typography.titleLarge)

            Text(t("soundStyle"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("normal", "slowed_reverb", "nightcore", "vaporwave").forEach { p ->
                    FilterChip(selected = settings.effectPreset == p, onClick = { update { it.copy(effectPreset = p) } }, label = { Text(t("fx_$p")) }, colors = chipColors)
                }
            }

            val custom = settings.effectPreset == "normal"
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("playbackSpeed"), modifier = Modifier.weight(1f))
                Text("%.2fx".format(settings.playbackSpeed), color = c.accent)
            }
            Slider(
                value = settings.playbackSpeed, valueRange = 0.5f..2f, steps = 29, enabled = custom,
                onValueChange = { v -> update { it.copy(playbackSpeed = AudioEffects.roundSpeed(v)) } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("preservePitch"), modifier = Modifier.weight(1f))
                Switch(checked = settings.preservePitch, enabled = custom, onCheckedChange = { v -> update { it.copy(preservePitch = v) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
            }
            TextButton(onClick = { update { it.copy(playbackSpeed = 1f, preservePitch = true, effectPreset = "normal") } }) { Text(t("resetSpeed")) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(t("equalizer"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioEffects.EQ_PRESETS.forEach { (name, bands) ->
                    FilterChip(selected = settings.equalizerPreset == name, onClick = { update { it.copy(equalizerPreset = name, equalizerBands = bands) } },
                        label = { Text(t("eq_$name")) }, colors = chipColors)
                }
            }
            val labels = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
            labels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Slider(
                        value = settings.equalizerBands.getOrElse(i) { 0 }.toFloat(), valueRange = -12f..12f, steps = 23,
                        onValueChange = { v ->
                            update { s ->
                                val b = s.equalizerBands.toMutableList().apply { while (size < 5) add(0); this[i] = v.roundToInt() }
                                s.copy(equalizerBands = b, equalizerPreset = "custom")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${settings.equalizerBands.getOrElse(i) { 0 }}dB", modifier = Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("volumeNormalizationLabel"))
                    Text(t("volumeNormalizationDesc"), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Switch(checked = settings.volumeNormalization, onCheckedChange = { v -> update { it.copy(volumeNormalization = v) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
            }
            Row(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("skipSilenceLabel"))
                    Text(t("skipSilenceDesc"), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Switch(checked = settings.skipSilence, onCheckedChange = { v -> update { it.copy(skipSilence = v) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
            }
        }
    }
}

// ------------------------------------------------------------------ devices: Cast, Bluetooth, Jam

@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val jam by graph.jam.state.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    val c = LocalSpotifreeColors.current
    var name by remember { mutableStateOf(graph.account.account.value?.name?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL) }
    var code by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(t("connectDevice"), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneAndroid, null, tint = if (!player.isCasting) c.accent else Color.White)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("thisPhone"), color = if (!player.isCasting) c.accent else Color.White)
                    Text(if (player.isCasting) t("castingNow") else t("listeningHere"), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Chromecast / Google TV", fontWeight = FontWeight.SemiBold)
                    Text(t("castHint"), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                AndroidView(
                    factory = { ctx ->
                        MediaRouteButton(ctx).also { runCatchingCancellable { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it) } }
                    },
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                runCatchingCancellable { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Text(t("bluetoothDevices"))
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, tint = c.accent)
                Spacer(Modifier.width(12.dp))
                Text(t("jamTitle"), style = MaterialTheme.typography.titleMedium)
            }
            Text(t("jamDesc"), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 6.dp))
            when {
                settings.jamServerUrl.isBlank() -> Text(t("jamNeedsServer"), color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                jam.status == "host" || jam.status == "guest" -> {
                    Text(t("jamRoomCode", jam.room.orEmpty()), style = MaterialTheme.typography.headlineSmall, color = c.accent)
                    Text(if (jam.status == "host") t("jamYouHost") else t("jamYouGuest"), color = c.textSecondary)
                    if (jam.members.isNotEmpty()) Text(t("jamMembers", jam.members.joinToString(", ")), modifier = Modifier.padding(top = 4.dp))
                    Button(onClick = { graph.jam.leave() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(t("jamLeave")) }
                }
                else -> {
                    OutlinedTextField(name, { name = it }, label = { Text(t("jamYourName")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { graph.jam.create(settings.jamServerUrl, name) }, enabled = jam.status != "connecting",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(t("jamCreate")) }
                    Row(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(code, { code = it.uppercase().take(8) }, label = { Text(t("jamCode")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { graph.jam.join(settings.jamServerUrl, code, name) }, enabled = code.length >= 4 && jam.status != "connecting") {
                            Text(t("jamJoin"))
                        }
                    }
                    jam.error?.let { Text(t("jamError", it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
                }
            }
        }
    }
}
