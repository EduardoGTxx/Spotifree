package com.spotifree.app.ui.player

import android.graphics.Bitmap
import android.os.SystemClock
import com.spotifree.app.data.lyrics.LyricsSync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.spotifree.app.ui.LocalGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dominant, darkened colour of the cover (background of the player). */
@Composable
fun rememberCoverColor(url: String?, fallback: Color): State<Color> {
    val context = LocalContext.current
    return produceState(fallback, url) {
        if (url.isNullOrBlank()) {
            value = fallback
            return@produceState
        }
        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            val req = ImageRequest.Builder(context).data(url).allowHardware(false).size(96).build()
            (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
        }
        val swatch = bitmap?.let { b ->
            withContext(Dispatchers.Default) {
                val p = Palette.from(b).maximumColorCount(12).generate()
                p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
            }
        }
        value = swatch?.let { s ->
            val c = Color(s.rgb)
            // Keep it dark enough for white text
            Color(c.red * 0.55f, c.green * 0.55f, c.blue * 0.55f, 1f)
        } ?: fallback
    }
}

/**
 * Playback position that advances every frame between the controller's samples (smooth karaoke).
 * Uses the monotonic clock, ignores the few-ms backward corrections of each new sample and
 * follows real seeks immediately (see [LyricsSync.smooth]).
 */
@Composable
fun rememberSmoothPosition(): State<Long> {
    val graph = LocalGraph.current
    val base by graph.player.position.collectAsStateWithLifecycle()
    val state by graph.player.state.collectAsStateWithLifecycle()
    val out = remember { mutableLongStateOf(base) }
    LaunchedEffect(base, state.isPlaying, state.speed) {
        val stamp = SystemClock.elapsedRealtime()
        out.longValue = LyricsSync.smooth(out.longValue, base, state.isPlaying)
        if (!state.isPlaying) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val elapsed = ((SystemClock.elapsedRealtime() - stamp) * state.speed).toLong().coerceIn(0L, 1500L)
            out.longValue = LyricsSync.smooth(out.longValue, base + elapsed, true)
        }
    }
    return out
}
