package com.spotifree.app.ui.workout

import com.spotifree.app.util.runCatchingCancellable

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotifree.app.AppGraph
import com.spotifree.app.data.model.Track
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.MenuContext
import com.spotifree.app.ui.common.LoadingBox
import com.spotifree.app.ui.common.SectionTitle
import com.spotifree.app.ui.common.TrackRow
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import com.spotifree.app.workout.WorkoutStreak
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class RestState(val totalSec: Int = 0, val leftSec: Int = 0, val running: Boolean = false)

class WorkoutViewModel(private val graph: AppGraph) : ViewModel() {
    private val _rest = MutableStateFlow(RestState())
    val rest: StateFlow<RestState> = _rest
    private val _tracks = MutableStateFlow<List<Track>?>(null)
    val tracks: StateFlow<List<Track>?> = _tracks
    private var job: Job? = null

    init {
        viewModelScope.launch {
            _tracks.value = runCatchingCancellable { graph.music.searchSongs("gym workout motivation music 130 bpm", 30) }.getOrDefault(emptyList())
                .filter { !graph.library.isBlocked(it) }
        }
    }

    private var generation = 0

    fun startRest(seconds: Int) {
        job?.cancel()
        val gen = ++generation
        markWorkoutDay()
        val s = graph.settings.current
        val duck = s.enableTGym && s.tgymLiveSync
        _rest.value = RestState(seconds, seconds, true)
        if (duck) graph.player.setDucked(true)
        job = viewModelScope.launch {
            try {
                while (_rest.value.leftSec > 0) {
                    delay(1000)
                    val left = _rest.value.leftSec - 1
                    _rest.value = _rest.value.copy(leftSec = left)
                    if (left in 1..3) beep(ToneGenerator.TONE_PROP_BEEP, 120)
                }
                beep(ToneGenerator.TONE_PROP_BEEP2, 450)
            } finally {
                // A newer rest may have started meanwhile: only the latest one resets the state.
                if (gen == generation) {
                    _rest.value = _rest.value.copy(running = false, leftSec = 0)
                    if (duck) graph.player.setDucked(false)
                }
            }
        }
    }

    fun skip() {
        job?.cancel()
    }

    private fun beep(tone: Int, ms: Int) {
        runCatchingCancellable {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(tone, ms)
            viewModelScope.launch { delay(ms + 100L); tg.release() }
        }
    }

    private fun markWorkoutDay() = viewModelScope.launch {
        graph.settings.update { WorkoutStreak.mark(it, LocalDate.now()) }
    }

    override fun onCleared() {
        job?.cancel()
    }
}

@Composable
fun WorkoutScreen() {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val c = LocalSpotifreeColors.current
    val vm: WorkoutViewModel = viewModel { WorkoutViewModel(graph) }
    val rest by vm.rest.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    val streak = WorkoutStreak.current(settings)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(t("workoutTitle"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp, 12.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF6D00), Color(0xFFD50000)))).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.LocalFireDepartment, null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(t("workoutStreak"), style = MaterialTheme.typography.titleMedium)
                    Text("$streak ${t("daysStreak")}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text(t("keepStreak"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SectionTitle(t("restTimer"))
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                val fraction = if (rest.totalSec > 0) rest.leftSec.toFloat() / rest.totalSec else 0f
                Canvas(Modifier.size(200.dp)) {
                    val stroke = 14.dp.toPx()
                    drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                    drawArc(c.accent, -90f, 360f * fraction, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (rest.running) "%d:%02d".format(rest.leftSec / 60, rest.leftSec % 60) else "%d s".format(settings.workoutRestSec),
                        fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text(if (rest.running) t("resting") else t("readyToLift"), color = c.textSecondary)
                }
            }
            if (rest.running) {
                OutlinedButton(onClick = { vm.skip() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(t("skipRest")) }
            } else {
                Button(onClick = { vm.startRest(settings.workoutRestSec) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(t("startRest", settings.workoutRestSec))
                }
                LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(30, 45, 60, 90, 120, 180)) { s ->
                        Box(Modifier.clip(CircleShape).background(c.elevated).clickable { vm.startRest(s) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("${s}s")
                        }
                    }
                }
                Text(t("tapToStartRest"), color = c.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (settings.tgymLiveSync) Text(t("audioDuckingActive"), color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp, 4.dp))
        }
        item { SectionTitle(t("gymTracksTitle")) }
        val list = tracks
        if (list == null) item { LoadingBox() }
        else itemsIndexed(list) { i, tr ->
            TrackRow(tr, onClick = { actions.play(list, i) }, isCurrent = player.current?.id == tr.id, onMore = { actions.showMenu(tr, MenuContext()) })
        }
    }
}
