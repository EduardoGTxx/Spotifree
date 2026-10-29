package com.spotifree.app.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Custom commands between the UI (MediaController) and the playback service. */
object PlaybackCommands {
    const val LIKE = "sf.like"
    const val SLEEP_TIMER = "sf.sleep" // args: minutes (Int, 0 = cancel) or endOfSong (Boolean)
    const val START_RADIO = "sf.radio" // args: trackId
    const val DUCK = "sf.duck" // args: on (Boolean)
    const val APPLY_SETTINGS = "sf.settings"

    const val ARG_MINUTES = "minutes"
    const val ARG_END_OF_SONG = "endOfSong"
    const val ARG_TRACK_ID = "trackId"
    const val ARG_ON = "on"

    // Session extras published by the service
    const val EXTRA_SLEEP_MODE = "sleepMode" // "", "timed", "end_of_song"
    const val EXTRA_SLEEP_ENDS_AT = "sleepEndsAt"
    const val EXTRA_LIKED = "liked"
    const val EXTRA_ERROR = "lastError"
    const val EXTRA_ERROR_AT = "lastErrorAt"
    const val EXTRA_RESOLVING = "resolving"

    val all = listOf(LIKE, SLEEP_TIMER, START_RADIO, DUCK, APPLY_SETTINGS).map { SessionCommand(it, Bundle.EMPTY) }
}

/** Runs a suspend block and exposes it as the Guava future Media3 callbacks expect. */
fun <T> CoroutineScope.listenableFuture(block: suspend () -> T): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    launch {
        try {
            future.set(block())
        } catch (tc: kotlin.coroutines.cancellation.CancellationException) {
            future.cancel(false)
            throw tc
        } catch (t: Throwable) {
            future.setException(t)
        }
    }
    return future
}
