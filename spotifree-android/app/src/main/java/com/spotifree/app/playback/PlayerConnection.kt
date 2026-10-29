package com.spotifree.app.playback

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.withTimeoutOrNull

import com.spotifree.app.util.runCatchingCancellable

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.spotifree.app.data.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Whole queue in play order. */
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val sleepMode: String = "",
    val sleepEndsAt: Long = 0,
    val speed: Float = 1f,
    val isCasting: Boolean = false,
)

sealed interface PlayerEvent {
    data class Unplayable(val title: String, val reason: String) : PlayerEvent
}

/**
 * The UI's handle on the playback service (a Media3 MediaController): observable state plus
 * the commands the screens need. Connected while the app is in the foreground.
 */
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ready = CompletableDeferred<MediaController>()
    private var ticker: Job? = null
    private var lastErrorAt = 0L

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerEvent> = _events

    /** True while some screen of the app is visible: the position is then sampled 4 times a second. */
    @Volatile private var foreground = false

    init {
        // Lifecycle observers must be added on the main thread; the graph may be built elsewhere
        scope.launch(Dispatchers.Main.immediate) {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                    if (controller == null) connect() else {
                        refresh()
                        startTicker()
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                }
            })
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = readExtras(extras)
                override fun onDisconnected(controller: MediaController) {
                    release()
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            val c = runCatchingCancellable { f.get() }.getOrNull() ?: run {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            ready.complete(c)
            readExtras(c.sessionExtras)
            refresh()
            startTicker()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        ticker?.cancel()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        ready = CompletableDeferred()
        _state.value = _state.value.copy(connected = false)
    }

    /**
     * Runs [block] once the controller is connected. A connection that never completes (service
     * killed mid-bind) no longer leaves the command waiting forever: after a timeout it reconnects
     * once, and drops the command if that fails too.
     */
    private fun withController(block: (MediaController) -> Unit) {
        scope.launch(Dispatchers.Main) {
            connect()
            val c = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() } ?: run {
                Log.w(TAG, "MediaController did not connect, retrying")
                release()
                connect()
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
            }
            if (c == null) {
                Log.w(TAG, "MediaController unavailable, command dropped")
                return@launch
            }
            block(c)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }

        override fun onPlayerError(error: PlaybackException) {
            refresh()
        }
    }

    private fun readExtras(extras: Bundle) {
        val errorAt = extras.getLong(PlaybackCommands.EXTRA_ERROR_AT)
        if (errorAt > lastErrorAt) {
            val first = lastErrorAt == 0L
            lastErrorAt = errorAt
            val raw = extras.getString(PlaybackCommands.EXTRA_ERROR).orEmpty()
            if (!first || System.currentTimeMillis() - errorAt < 5000) {
                _events.tryEmit(PlayerEvent.Unplayable(raw.substringBefore('|'), raw.substringAfter('|', "")))
            }
        }
        _state.value = _state.value.copy(
            sleepMode = extras.getString(PlaybackCommands.EXTRA_SLEEP_MODE).orEmpty(),
            sleepEndsAt = extras.getLong(PlaybackCommands.EXTRA_SLEEP_ENDS_AT),
        )
    }

    /** Play-order positions of the queue (shuffle is a physical reorder, so this is index order). */
    private fun refresh(rebuildQueue: Boolean = true) {
        val c = controller ?: return
        val count = c.mediaItemCount
        val queue = if (rebuildQueue || _state.value.queue.size != count) {
            ArrayList<Track>(count).also { list -> for (i in 0 until count) list += MediaItems.toTrack(c.getMediaItemAt(i)) }
        } else _state.value.queue
        _state.value = _state.value.copy(
            connected = true,
            current = c.currentMediaItem?.let { MediaItems.toTrack(it) },
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it > 0 } ?: ((c.currentMediaItem?.mediaMetadata?.durationMs) ?: 0L),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            queueIndex = c.currentMediaItemIndex,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem() || c.currentPosition > 3000,
            speed = c.playbackParameters.speed,
            isCasting = c.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE,
        )
        _position.value = c.currentPosition
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                controller?.let { c ->
                    _position.value = c.currentPosition
                    val d = c.duration
                    if (d > 0 && d != _state.value.durationMs) _state.value = _state.value.copy(durationMs = d)
                }
                // Visible UI: smooth lyrics and progress. In the background only Jam and the widget
                // read the position, so a sample per second is enough (4x fewer wake-ups).
                delay(if (_state.value.isPlaying && foreground) 250 else 1000)
            }
        }
    }

    // ------------------------------------------------------------------ commands

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val items = tracks.map { MediaItems.toMediaItem(it) }
        val start = if (shuffle && startIndex == 0) tracks.indices.random() else startIndex.coerceIn(0, tracks.size - 1)
        withController { c ->
            c.setMediaItems(items, start, 0)
            if (shuffle) c.shuffleModeEnabled = true
            c.prepare()
            c.play()
        }
    }

    /** Plays one song and lets the radio continue after it. */
    fun playSingle(track: Track) {
        withController { c ->
            c.setMediaItems(listOf(MediaItems.toMediaItem(track)), 0, 0)
            c.prepare()
            c.play()
            c.sendCustomCommand(SessionCommand(PlaybackCommands.START_RADIO, Bundle.EMPTY), Bundle.EMPTY)
        }
    }

    fun startRadio(track: Track) = playSingle(track)

    fun playNext(track: Track) = withController { c ->
        val item = MediaItems.toMediaItem(track)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item); c.prepare(); c.play()
        } else c.addMediaItem(c.currentMediaItemIndex + 1, item)
    }

    fun addToQueue(track: Track) = withController { c ->
        val item = MediaItems.toMediaItem(track)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item); c.prepare(); c.play()
        } else c.addMediaItem(item)
    }

    fun addAllToQueue(tracks: List<Track>) = withController { c ->
        val items = tracks.map { MediaItems.toMediaItem(it) }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else c.addMediaItems(items)
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) c.pause()
        else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun play() = withController { it.play() }

    /** Used by the Bluetooth auto-resume: plays the restored queue if there is one. */
    fun resumeWhenReady() = withController { c -> if (c.mediaItemCount > 0 && !c.isPlaying) { c.prepare(); c.play() } }
    fun pause() = withController { it.pause() }
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); _position.value = ms }
    fun skipToQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0); c.prepare(); c.play()
        }
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount && from != to) c.moveMediaItem(from, to)
    }

    fun removeQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount && index != c.currentMediaItemIndex) c.removeMediaItem(index)
    }

    fun clearUpcoming() = withController { c ->
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
    }

    fun toggleLikeCurrent() = withController { it.sendCustomCommand(SessionCommand(PlaybackCommands.LIKE, Bundle.EMPTY), Bundle.EMPTY) }

    fun setSleepTimer(minutes: Int) = withController {
        it.sendCustomCommand(
            SessionCommand(PlaybackCommands.SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackCommands.ARG_MINUTES, minutes) },
        )
    }

    fun setSleepEndOfSong() = withController {
        it.sendCustomCommand(
            SessionCommand(PlaybackCommands.SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply { putBoolean(PlaybackCommands.ARG_END_OF_SONG, true) },
        )
    }

    fun setDucked(on: Boolean) = withController {
        it.sendCustomCommand(SessionCommand(PlaybackCommands.DUCK, Bundle.EMPTY), Bundle().apply { putBoolean(PlaybackCommands.ARG_ON, on) })
    }

    private companion object {
        const val TAG = "PlayerConnection"
        const val CONNECT_TIMEOUT_MS = 8_000L
    }

    /** Queue items by index (for the queue sheet). */
    fun itemAt(index: Int): MediaItem? = controller?.takeIf { index in 0 until it.mediaItemCount }?.getMediaItemAt(index)
}
