package com.spotifree.app.ui.player

import android.graphics.Bitmap
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

/** Playback position that advances every frame between the controller's 250 ms updates (smooth karaoke). */
@Composable
fun rememberSmoothPosition(): State<Long> {
    val graph = LocalGraph.current
    val base by graph.player.position.collectAsStateWithLifecycle()
    val state by graph.player.state.collectAsStateWithLifecycle()
    val stamp = remember(base) { System.currentTimeMillis() }
    val out = remember { mutableLongStateOf(base) }
    LaunchedEffect(base, state.isPlaying, state.speed) {
        out.longValue = base
        if (!state.isPlaying) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val elapsed = ((System.currentTimeMillis() - stamp) * state.speed).toLong()
            out.longValue = base + elapsed.coerceAtMost(1500)
        }
    }
    return out
}
