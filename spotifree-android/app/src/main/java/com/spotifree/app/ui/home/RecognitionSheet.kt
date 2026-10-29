package com.spotifree.app.ui.home

import com.spotifree.app.util.runCatchingCancellable

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.recognition.RecognitionException
import com.spotifree.app.data.recognition.RecognizedSong
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.SettingsRoute
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** "¿Qué suena?": listen 8 seconds, identify with AudD / ACRCloud, then play it from YouTube. */
@Composable
fun RecognitionSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val settings = graph.settings.current
    var phase by remember { mutableStateOf("idle") } // idle | listening | identifying | found | notfound | error
    var level by remember { mutableFloatStateOf(0f) }
    var song by remember { mutableStateOf<RecognizedSong?>(null) }
    var match by remember { mutableStateOf<Track?>(null) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val scale by animateFloatAsState(1f + level * 0.35f, label = "mic")

    fun start() {
        phase = "listening"
        job = scope.launch {
            try {
                val file = graph.recognizer.record(8000) { level = it }
                phase = "identifying"
                val r = graph.recognizer.identify(file, settings.recognitionProvider, settings.recognitionApiKey)
                if (r == null) {
                    phase = "notfound"
                } else {
                    song = r
                    phase = "found"
                    match = runCatchingCancellable { graph.music.findBestMatch(r.artist, r.title) }.getOrNull()
                }
            } catch (e: RecognitionException) {
                error = when (e.code) {
                    "permission" -> strings["micPermissionDenied"]
                    "config" -> strings["recognitionNeedsKey"]
                    else -> e.message ?: "error"
                }
                phase = "error"
            } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
                throw ec
            } catch (e: Exception) {
                error = e.message ?: "error"
                phase = "error"
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else {
            error = strings["micPermissionDenied"]
            phase = "error"
        }
    }

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }, containerColor = c.elevated, contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(t("whatsPlaying"), style = MaterialTheme.typography.titleLarge)
            if (settings.recognitionApiKey.isBlank()) {
                Text(t("recognitionNeedsKey"), textAlign = TextAlign.Center, color = c.textSecondary)
                Button(onClick = { onDismiss(); actions.nav.navigate(SettingsRoute) }) { Text(t("openSettings")) }
                return@Column
            }
            when (phase) {
                "idle", "listening" -> {
                    Box(
                        Modifier.size(140.dp).scale(scale).clip(CircleShape).background(c.accent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Mic, null, modifier = Modifier.size(56.dp), tint = androidx.compose.ui.graphics.Color.White) }
                    Text(if (phase == "idle") t("tapToListen") else t("listening"), color = c.textSecondary)
                    if (phase == "idle") Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text(t("startListening")) }
                }
                "identifying" -> {
                    CircularProgressIndicator(color = c.accent)
                    Text(t("identifying"), color = c.textSecondary)
                }
                "found" -> song?.let { s ->
                    Artwork(s.coverUrl ?: match?.artworkUrl, size = 160.dp, shape = RoundedCornerShape(8.dp))
                    Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(s.artist, color = c.textSecondary, textAlign = TextAlign.Center)
                    Row {
                        Button(enabled = match != null, onClick = {
                            match?.let { actions.play(listOf(it)); actions.openPlayer() }
                            onDismiss()
                        }) { Text(t("playAll")) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(enabled = match != null, onClick = { match?.let { actions.toggleLike(it) } }) { Text(t("addToLiked")) }
                    }
                    OutlinedButton(onClick = { phase = "idle"; song = null; match = null }) { Text(t("tryAgain")) }
                }
                "notfound" -> {
                    Text(t("songNotRecognized"), textAlign = TextAlign.Center)
                    Button(onClick = { start() }) { Text(t("tryAgain")) }
                }
                "error" -> {
                    Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = { phase = "idle" }) { Text(t("tryAgain")) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
