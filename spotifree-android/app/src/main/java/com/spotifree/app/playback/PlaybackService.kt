package com.spotifree.app.playback

import com.spotifree.app.util.runCatchingCancellable

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.spotifree.app.AppGraph
import com.spotifree.app.MainActivity
import com.spotifree.app.R
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.prefs.AppSettings
import com.spotifree.app.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The heart of the app: one ExoPlayer inside a MediaLibraryService. The queue, playback,
 * notification, lock screen, Android Auto, Bluetooth controls and the widget all live here, so
 * music keeps playing when the UI is closed.
 */
class PlaybackService : MediaLibraryService() {
    private lateinit var graph: AppGraph
    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: ShufflingPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effects = AudioEffects()
    private lateinit var browseTree: BrowseTree
    private var cast: CastSupport? = null

    // Listening time of the current item (for history / stats)
    private var listenedMs = 0L
    private var playingSince = 0L
    private var historyTrack: Track? = null

    // Error recovery
    private val retries = HashMap<String, Int>()

    // Radio autoplay
    private var radioJob: Job? = null
    private var radioSeedId: String? = null

    // Sleep timer
    private var sleepMode = ""
    private var sleepEndsAt = 0L
    private var sleepJob: Job? = null

    // Volume shaping (crossfade-style fades + TGym ducking)
    private var ducked = false
    private var volumeJob: Job? = null

    private var saveQueueJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        val s = graph.settings.current
        val cache = TrackResolver.cache(this, STREAM_CACHE_MB)
        val dataSourceFactory = TrackResolver.dataSourceFactory(this, graph.http, graph.trackResolver, cache) { true }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(s.autoPauseOnNoisy)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(playerListener)
        sessionPlayer = ShufflingPlayer(player)
        applySettings(s)

        browseTree = BrowseTree(this, graph)

        val sessionIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setSessionActivity(sessionIntent)
            .setMediaButtonPreferences(ImmutableList.of(likeButton(false)))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_spotifree)
            }
        )

        cast = runCatchingCancellable { CastSupport(this, graph, player) { p -> session?.player = if (p === player) sessionPlayer else p } }
            .onFailure { Log.w(TAG, "Cast unavailable", it) }.getOrNull()

        scope.launch { restoreQueue() }
        scope.launch {
            graph.settings.settings.collectLatest { applySettings(it) }
        }
        scope.launch {
            graph.library.likedIds.map { ids -> player.currentMediaItem?.mediaId?.let { it in ids } ?: false }
                .distinctUntilChanged()
                .collectLatest { liked -> updateLikeButton(liked) }
        }
        scope.launch {
            graph.playbackEvents.duck.collectLatest { on -> setDucked(on) }
        }
        // TGym rest: restore the volume when its end time passes even if REST_END never arrives
        scope.launch {
            val events = graph.playbackEvents
            events.restEndsAt.collectLatest { endsAt ->
                if (endsAt <= 0L) return@collectLatest
                delay((endsAt - System.currentTimeMillis()).coerceAtLeast(0L) + 1_500L)
                if (events.restEndsAt.value == endsAt && events.duck.value) {
                    events.restEndsAt.value = 0L
                    events.duck.value = false
                    events.chime.tryEmit(Unit)
                }
            }
        }
        scope.launch { graph.playbackEvents.chime.collect { playChime() } }
        startVolumeLoop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the app from recents keeps the music; with nothing playing the service ends
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        flushHistory()
        saveQueueNow()
        cast?.release()
        session?.release()
        session = null
        effects.release()
        player.removeListener(playerListener)
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ settings

    private var appliedSettings: AppSettings? = null

    private fun applySettings(s: AppSettings) {
        val prev = appliedSettings
        appliedSettings = s
        player.skipSilenceEnabled = s.skipSilence
        player.setHandleAudioBecomingNoisy(s.autoPauseOnNoisy)
        val (speed, pitch) = AudioEffects.speedPitch(s)
        if (player.playbackParameters.speed != speed || player.playbackParameters.pitch != pitch) {
            player.playbackParameters = PlaybackParameters(speed, pitch)
        }
        effects.attach(player.audioSessionId, s)
        if (prev != null && prev.castReceiverAppId != s.castReceiverAppId) cast?.receiverChanged()
    }

    // ------------------------------------------------------------------ player events

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            flushHistory()
            historyTrack = mediaItem?.let { MediaItems.toTrack(it) }
            listenedMs = 0
            playingSince = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L
            mediaItem?.mediaId?.let { id -> updateLikeButton(id in graph.library.likedIds.value) }
            mediaItem?.let { item ->
                val t = MediaItems.toTrack(item)
                if (graph.library.isBlocked(t) && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    return
                }
            }
            maybeQueueRadio()
            prefetchNext()
            scheduleSaveQueue()
            WidgetUpdater.update(this@PlaybackService, player)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // "Stop at the end of this song": the player paused itself at the end of the item
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepMode == "end_of_song") {
                setSleep("", 0)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (isPlaying) playingSince = now
            else if (playingSince > 0) {
                listenedMs += now - playingSince
                playingSince = 0
            }
            if (!isPlaying) saveQueueNow()
            WidgetUpdater.update(this@PlaybackService, player)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleSaveQueue()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                flushHistory()
                val s = graph.settings.current
                if (s.autoplayRadio && player.repeatMode == Player.REPEAT_MODE_OFF) {
                    maybeQueueRadio(force = true, playAfter = true)
                }
            }
            if (playbackState == Player.STATE_READY) {
                player.currentMediaItem?.mediaId?.let { retries.remove(it) }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects.attach(audioSessionId, graph.settings.current)
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private fun handleError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        val track = MediaItems.toTrack(item)
        val unavailable = generateSequence(error as Throwable) { it.cause }.filterIsInstance<TrackUnavailableException>().firstOrNull()
        val http = generateSequence(error as Throwable) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val attempt = (retries[id] ?: 0) + 1
        retries[id] = attempt
        Log.w(TAG, "Playback error on ${track.title} (attempt $attempt): ${error.errorCodeName} ${unavailable?.reason ?: http?.responseCode ?: error.message}")

        val position = player.currentPosition
        val vid = track.youtubeId
        val retryable = unavailable == null || unavailable.reason == "bot_check" || unavailable.reason.startsWith("http_")
        if (retryable && attempt <= 2 && vid != null) {
            // Expired / forbidden URL: drop it and resolve again (second try skips the extractor)
            graph.streams.invalidate(vid)
            if (attempt == 2 || http?.responseCode == 403) graph.trackResolver.avoidNewPipe.add(vid)
            player.prepare()
            player.seekTo(player.currentMediaItemIndex, position)
            player.play()
            return
        }
        publishError(track.title, unavailable?.reason ?: "error")
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        } else if (graph.settings.current.autoplayRadio) {
            maybeQueueRadio(force = true, playAfter = true)
        }
    }

    private fun publishError(title: String, reason: String) {
        val extras = Bundle(session?.sessionExtras ?: Bundle.EMPTY).apply {
            putString(PlaybackCommands.EXTRA_ERROR, "$title|$reason")
            putLong(PlaybackCommands.EXTRA_ERROR_AT, System.currentTimeMillis())
        }
        session?.setSessionExtras(extras)
    }

    // ------------------------------------------------------------------ history

    private fun flushHistory() {
        val track = historyTrack ?: return
        if (playingSince > 0) {
            listenedMs += SystemClock.elapsedRealtime() - playingSince
            playingSince = SystemClock.elapsedRealtime()
        }
        val seconds = (listenedMs / 1000).toInt()
        val durationSec = if (track.durationSec > 0) track.durationSec else (player.duration.takeIf { it > 0 }?.div(1000)?.toInt() ?: 0)
        val counts = seconds >= 30 || (durationSec in 1..59 && seconds >= durationSec / 2)
        if (counts) {
            historyTrack = null
            listenedMs = 0
            graph.scope.launch {
                graph.library.recordPlay(track, seconds)
                graph.downloads.onPlayed(track, seconds, durationSec)
            }
        }
    }

    // ------------------------------------------------------------------ radio / prefetch

    private fun maybeQueueRadio(force: Boolean = false, playAfter: Boolean = false) {
        val s = graph.settings.current
        if (!s.autoplayRadio || s.offlineMode) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF || sessionPlayer.shuffleModeEnabled && !force) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (!force && remaining > 1) return
        val seedItem = player.currentMediaItem ?: (if (player.mediaItemCount > 0) player.getMediaItemAt(player.mediaItemCount - 1) else null) ?: return
        val seed = MediaItems.toTrack(seedItem)
        if (radioJob?.isActive == true || radioSeedId == seed.id) return
        radioSeedId = seed.id
        radioJob = scope.launch {
            val resolvedSeed = seed.youtubeId?.let { seed } ?: graph.library.track(seed.id)?.let { graph.library.resolveOnYouTube(it) }
            val vid = resolvedSeed?.youtubeId ?: return@launch
            val existing = HashSet<String>()
            for (i in 0 until player.mediaItemCount) existing += player.getMediaItemAt(i).mediaId
            val radio = withContext(Dispatchers.IO) { runCatchingCancellable { graph.music.radio(vid) }.getOrDefault(emptyList()) }
                .filter { it.id !in existing && !graph.library.isBlocked(it) }
                .take(20)
            if (radio.isEmpty()) return@launch
            graph.library.saveAll(radio)
            val wasEnded = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(radio.map { MediaItems.toMediaItem(it) })
            if (playAfter && wasEnded) {
                player.seekTo(player.currentMediaItemIndex + 1, 0)
                player.prepare()
                player.play()
            }
        }
    }

    /** Resolves the next song's stream ahead of time so the transition is instant. */
    private fun prefetchNext() {
        if (graph.settings.current.offlineMode) return
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val track = MediaItems.toTrack(player.getMediaItemAt(next))
        if (track.isLocal) return
        graph.scope.launch(Dispatchers.IO) {
            runCatchingCancellable {
                val t = track.youtubeId?.let { track } ?: graph.library.track(track.id)?.let { graph.library.resolveOnYouTube(it) }
                t?.youtubeId?.let { graph.streams.resolve(it, graph.settings.current.audioQuality) }
            }
        }
    }

    // ------------------------------------------------------------------ queue persistence

    private fun scheduleSaveQueue() {
        saveQueueJob?.cancel()
        saveQueueJob = scope.launch {
            delay(1500)
            saveQueueNow()
        }
    }

    private fun saveQueueNow() {
        if (!::player.isInitialized) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val shuffle = sessionPlayer.shuffleModeEnabled
        val original = sessionPlayer.originalOrder()?.joinToString("\n")
        val repeat = player.repeatMode
        getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE).edit()
            .putInt("index", index)
            .putLong("position", position)
            .putBoolean("shuffle", shuffle)
            .putString("original", original)
            .putInt("repeat", repeat)
            .apply()
        graph.scope.launch { graph.db.queue().replace(ids) }
    }

    private suspend fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val items = savedQueueItems() ?: return
        if (player.mediaItemCount > 0) return
        val prefs = getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
        player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        player.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
        sessionPlayer.restore(prefs.getBoolean("shuffle", false), prefs.getString("original", null)?.split('\n')?.filter { it.isNotBlank() })
        player.prepare()
    }

    private suspend fun savedQueueItems(): MediaSession.MediaItemsWithStartPosition? {
        val ids = graph.db.queue().all().map { it.trackId }
        if (ids.isEmpty()) return null
        val tracks = graph.library.tracksByIds(ids)
        if (tracks.isEmpty()) return null
        val prefs = getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
        val index = prefs.getInt("index", 0).coerceIn(0, tracks.size - 1)
        return MediaSession.MediaItemsWithStartPosition(tracks.map { MediaItems.toMediaItem(it) }, index, prefs.getLong("position", 0))
    }

    // ------------------------------------------------------------------ sleep timer

    private fun setSleep(mode: String, endsAt: Long) {
        sleepMode = mode
        sleepEndsAt = endsAt
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = mode == "end_of_song"
        if (mode == "timed") {
            sleepJob = scope.launch {
                while (isActive) {
                    val left = sleepEndsAt - System.currentTimeMillis()
                    if (left <= 0) {
                        fadeOutAndPause()
                        setSleep("", 0)
                        break
                    }
                    delay(minOf(left, 1000L))
                }
            }
        }
        val extras = Bundle(session?.sessionExtras ?: Bundle.EMPTY).apply {
            putString(PlaybackCommands.EXTRA_SLEEP_MODE, mode)
            putLong(PlaybackCommands.EXTRA_SLEEP_ENDS_AT, endsAt)
        }
        session?.setSessionExtras(extras)
    }

    private suspend fun fadeOutAndPause() {
        val start = player.volume
        for (i in 10 downTo 0) {
            player.volume = start * i / 10f
            delay(300)
        }
        player.pause()
        player.volume = start
    }

    // ------------------------------------------------------------------ volume shaping

    private fun setDucked(on: Boolean) {
        ducked = on
    }

    /** Two short tones: "rest is over". */
    private suspend fun playChime() {
        val tone = runCatchingCancellable { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80) }.getOrNull() ?: return
        try {
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            delay(250)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 300)
            delay(400)
        } finally {
            tone.release()
        }
    }

    private fun startVolumeLoop() {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            while (isActive) {
                val s = graph.settings.current
                val duck = if (ducked) s.tgymDuckingLevel.coerceIn(0f, 1f) else 1f
                var fade = 1f
                val fadeMs = s.crossfadeSec * 1000L
                if (fadeMs > 0 && player.isPlaying) {
                    val pos = player.currentPosition
                    val dur = player.duration
                    if (pos < fadeMs && player.currentMediaItemIndex > 0) fade = (pos.toFloat() / fadeMs).coerceIn(0.05f, 1f)
                    if (dur > fadeMs * 2 && dur - pos < fadeMs && player.hasNextMediaItem()) {
                        fade = minOf(fade, ((dur - pos).toFloat() / fadeMs).coerceIn(0f, 1f))
                    }
                }
                if (sleepJob?.isActive != true || sleepEndsAt - System.currentTimeMillis() > 4000) {
                    val target = duck * fade
                    if (kotlin.math.abs(player.volume - target) > 0.01f) player.volume = target
                }
                delay(if (fadeMs > 0 || ducked) 100 else 500)
            }
        }
    }

    // ------------------------------------------------------------------ notification like button

    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(if (liked) R.string.unlike else R.string.like))
            .setSessionCommand(SessionCommand(PlaybackCommands.LIKE, Bundle.EMPTY))
            .build()

    private fun updateLikeButton(liked: Boolean) {
        session?.setMediaButtonPreferences(ImmutableList.of(likeButton(liked)))
        val extras = Bundle(session?.sessionExtras ?: Bundle.EMPTY).apply { putBoolean(PlaybackCommands.EXTRA_LIKED, liked) }
        session?.setSessionExtras(extras)
    }

    // ------------------------------------------------------------------ session callback

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            PlaybackCommands.all.forEach { commands.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackCommands.LIKE -> {
                    val item = player.currentMediaItem ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    val track = MediaItems.toTrack(item)
                    graph.scope.launch {
                        val liked = graph.library.toggleLike(track)
                        if (liked) graph.downloads.onLiked(track)
                    }
                }
                PlaybackCommands.SLEEP_TIMER -> {
                    if (args.getBoolean(PlaybackCommands.ARG_END_OF_SONG)) {
                        setSleep("end_of_song", 0)
                    } else {
                        val minutes = args.getInt(PlaybackCommands.ARG_MINUTES)
                        if (minutes <= 0) setSleep("", 0)
                        else setSleep("timed", System.currentTimeMillis() + minutes * 60_000L)
                    }
                }
                PlaybackCommands.START_RADIO -> {
                    radioSeedId = null
                    maybeQueueRadio(force = true)
                }
                PlaybackCommands.DUCK -> setDucked(args.getBoolean(PlaybackCommands.ARG_ON))
                PlaybackCommands.APPLY_SETTINGS -> applySettings(graph.settings.current)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.listenableFuture {
            val tracks = browseTree.expandToTracks(mediaItems)
            graph.library.saveAll(tracks)
            tracks.map { MediaItems.toMediaItem(it) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.listenableFuture {
            // Android Auto plays a song "inside" a playlist: queue the whole list from that song
            browseTree.expandContext(mediaItems)?.let { (tracks, index) ->
                graph.library.saveAll(tracks)
                return@listenableFuture MediaSession.MediaItemsWithStartPosition(tracks.map { MediaItems.toMediaItem(it) }, index, 0)
            }
            val tracks = browseTree.expandToTracks(mediaItems)
            graph.library.saveAll(tracks)
            radioSeedId = null
            MediaSession.MediaItemsWithStartPosition(
                tracks.map { MediaItems.toMediaItem(it) },
                if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
                if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs,
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.listenableFuture {
            savedQueueItems() ?: MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.listenableFuture {
            val children = browseTree.children(parentId)
            val from = (page * pageSize).coerceAtMost(children.size)
            val to = (from + pageSize).coerceAtMost(children.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.listenableFuture {
            val item = browseTree.item(mediaId)
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.listenableFuture {
            val count = browseTree.search(query)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.listenableFuture {
            val all = browseTree.searchResults(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val QUEUE_PREFS = "queue_state"
        private const val STREAM_CACHE_MB = 400
    }
}
