package com.spotifree.app.widget

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.media3.common.Player
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/** Keeps the home screen widget in sync with the player (only when a widget is placed). */
object WidgetUpdater {
    const val COVER_FILE = "widget_cover.png"
    private const val PREFS = "widget_state"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastCoverUrl: String? = null

    data class State(val title: String, val artist: String, val isPlaying: Boolean)

    fun read(context: Context): State {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(p.getString("title", "") ?: "", p.getString("artist", "") ?: "", p.getBoolean("playing", false))
    }

    fun update(context: Context, player: Player) {
        val md = player.mediaMetadata
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        val playing = player.isPlaying
        val coverUrl = md.artworkUri?.toString()
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            val ids = runCatchingCancellable { GlanceAppWidgetManager(app).getGlanceIds(SpotifreeWidget::class.java) }.getOrDefault(emptyList())
            if (ids.isEmpty()) return@launch
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", title).putString("artist", artist).putBoolean("playing", playing).apply()
            if (coverUrl != lastCoverUrl) {
                lastCoverUrl = coverUrl
                val bitmap = coverUrl?.let { loadCover(app, it) }
                val file = File(app.filesDir, COVER_FILE)
                if (bitmap != null) FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } else file.delete()
            }
            SpotifreeWidget().updateAll(app)
        }
    }

    private suspend fun loadCover(context: Context, url: String): Bitmap? = runCatchingCancellable {
        val result = ImageLoader(context).execute(
            ImageRequest.Builder(context).data(url).size(192).allowHardware(false).build()
        )
        (result as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()
}
