package com.spotifree.app.playback

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastContext
import com.spotifree.app.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Chromecast: while a Cast session is connected the media session drives a CastPlayer instead
 * of ExoPlayer. The receiver gets direct audio URLs (resolved here, AAC first), so the Default
 * Media Receiver works without any server. Back on the phone, playback continues paused at the
 * position the receiver reached.
 */
class CastSupport(
    private val context: Context,
    private val graph: AppGraph,
    private val local: ExoPlayer,
    private val switchPlayer: (Player) -> Unit,
) : SessionAvailabilityListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Suppress("DEPRECATION")
    private val castPlayer = CastPlayer(CastContext.getSharedInstance(context))
    private var castStartIndex = 0

    init {
        castPlayer.setSessionAvailabilityListener(this)
        if (castPlayer.isCastSessionAvailable) onCastSessionAvailable()
    }

    override fun onCastSessionAvailable() {
        if (local.mediaItemCount == 0) {
            switchPlayer(castPlayer)
            return
        }
        val items = (0 until local.mediaItemCount).map { local.getMediaItemAt(it) }
        val index = local.currentMediaItemIndex.coerceAtLeast(0)
        val position = local.currentPosition
        val play = local.playWhenReady
        local.pause()
        castStartIndex = index
        scope.launch {
            val window = items.subList(index, minOf(items.size, index + 25))
            val first = resolve(window.first())
            if (first == null) {
                graph.messages.post(context.getString(com.spotifree.app.R.string.cast_cannot_play))
                return@launch
            }
            castPlayer.setMediaItems(listOf(first), 0, position)
            castPlayer.playWhenReady = play
            castPlayer.prepare()
            switchPlayer(castPlayer)
            for (item in window.drop(1)) {
                resolve(item)?.let { castPlayer.addMediaItem(it) }
            }
        }
    }

    override fun onCastSessionUnavailable() {
        val position = castPlayer.currentPosition
        val castIndex = castPlayer.currentMediaItemIndex.coerceAtLeast(0)
        switchPlayer(local)
        val target = (castStartIndex + castIndex).coerceIn(0, (local.mediaItemCount - 1).coerceAtLeast(0))
        if (local.mediaItemCount > 0) {
            local.seekTo(target, position)
            local.pause()
        }
    }

    private suspend fun resolve(item: MediaItem): MediaItem? {
        val track = MediaItems.toTrack(item)
        if (track.isLocal) return null
        val videoId = track.youtubeId
            ?: graph.library.track(track.id)?.let { graph.library.resolveOnYouTube(it) }?.youtubeId
            ?: return null
        val stream = runCatchingCancellable { graph.streams.resolve(videoId, "cast") }
            .onFailure { Log.w("CastSupport", "resolve failed", it) }
            .getOrNull() ?: return null
        return item.buildUpon().setUri(stream.url).setMimeType(stream.mimeType).build()
    }

    /** The receiver app id is read when the Cast framework starts (next app launch). */
    fun receiverChanged() = Unit

    fun release() {
        castPlayer.setSessionAvailabilityListener(null)
        castPlayer.release()
        scope.cancel()
    }
}
