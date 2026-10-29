package com.spotifree.app.data.jam

import com.spotifree.app.util.runCatchingCancellable

import com.spotifree.app.data.model.Track
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class JamState(
    val status: String = "idle", // idle | connecting | host | guest
    val room: String? = null,
    val members: List<String> = emptyList(),
    val error: String? = null,
)

data class JamPlayback(val track: Track, val isPlaying: Boolean, val positionSec: Double, val sentAt: Long)

sealed interface JamEvent {
    data class Suggestion(val track: Track, val from: String) : JamEvent
    data class Follow(val playback: JamPlayback) : JamEvent
    data class Info(val message: String) : JamEvent
}

/**
 * "Escuchar juntos" over the user's own relay server (jam-server). Same JSON protocol as the
 * previous app: the host broadcasts its playback, guests follow it and can suggest songs.
 */
class JamClient(http: OkHttpClient, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    // WebSocket pings detect a dead connection (phone lost signal) within ~20 s
    private val http = http.newBuilder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnectJob: Job? = null

    /** What to resend after a dropped connection (a guest re-joins the same room). */
    private var lastServer: String? = null
    private var lastRoom: String? = null
    private var lastName: String = ""
    private var userLeft = false
    private val _state = MutableStateFlow(JamState())
    val state: StateFlow<JamState> = _state
    private val _events = MutableSharedFlow<JamEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<JamEvent> = _events

    /** Provided by the UI: the host's current playback. */
    var currentPlayback: () -> JamPlayback? = { null }

    fun create(serverUrl: String, name: String) {
        lastServer = serverUrl
        lastRoom = null
        lastName = name
        connect(serverUrl) { send(buildJsonObject { put("type", "create"); put("name", name) }.toString()) }
    }

    fun join(serverUrl: String, room: String, name: String) {
        lastServer = serverUrl
        lastRoom = room.trim().uppercase()
        lastName = name
        connect(serverUrl) {
            send(buildJsonObject { put("type", "join"); put("room", room.trim().uppercase()); put("name", name) }.toString())
        }
    }

    fun suggest(track: Track, from: String): Boolean {
        if (_state.value.status != "guest") return false
        send(buildJsonObject { put("type", "suggest"); put("track", trackJson(track)); put("from", from) }.toString())
        return true
    }

    fun broadcastNow() {
        if (_state.value.status != "host") return
        val p = currentPlayback() ?: return
        send(buildJsonObject {
            put("type", "state")
            putJsonObject("state") {
                put("track", trackJson(p.track)); put("isPlaying", p.isPlaying); put("position", p.positionSec); put("sentAt", System.currentTimeMillis())
            }
        }.toString())
    }

    fun leave() {
        userLeft = true
        lastRoom = null
        reconnectJob?.cancel()
        closeSocket()
        _state.value = JamState()
    }

    private fun closeSocket() {
        heartbeat?.cancel()
        socket?.let {
            runCatchingCancellable { it.send("""{"type":"leave"}""") }
            it.close(1000, "leave")
        }
        socket = null
    }

    private fun send(text: String) {
        socket?.send(text)
    }

    private fun connect(serverUrl: String, onOpen: () -> Unit) {
        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            _state.value = JamState(error = "config")
            return
        }
        reconnectJob?.cancel()
        closeSocket()
        userLeft = false
        _state.value = JamState(status = "connecting")
        socket = http.newWebSocket(Request.Builder().url(serverUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatchingCancellable { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
                fun s(k: String) = msg[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }
                when (s("type")) {
                    "created" -> {
                        _state.value = JamState(status = "host", room = s("room"))
                        heartbeat = scope.launch(Dispatchers.Main) {
                            while (isActive) {
                                broadcastNow()
                                delay(5000)
                            }
                        }
                    }
                    "joined" -> {
                        reconnectAttempt = 0
                        _state.value = JamState(status = "guest", room = s("room"))
                        (msg["state"] as? JsonObject)?.let { playbackFrom(it) }?.let { _events.tryEmit(JamEvent.Follow(it)) }
                    }
                    "members" -> _state.value = _state.value.copy(members = (msg["members"] as? JsonArray)?.mapNotNull { m ->
                        when (m) {
                            is JsonObject -> m["name"]?.jsonPrimitive?.contentOrNull
                            else -> m.jsonPrimitive.contentOrNull
                        }
                    }.orEmpty())
                    "state" -> if (_state.value.status == "guest") (msg["state"] as? JsonObject)?.let { playbackFrom(it) }?.let { _events.tryEmit(JamEvent.Follow(it)) }
                    "suggest" -> if (_state.value.status == "host") (msg["track"] as? JsonObject)?.let { trackFrom(it) }?.let {
                        _events.tryEmit(JamEvent.Suggestion(it, s("from") ?: "?"))
                    }
                    "error" -> {
                        val message = s("message") ?: "error"
                        if (_state.value.status == "connecting") leave()
                        _state.value = _state.value.copy(error = message)
                        _events.tryEmit(JamEvent.Info(s("message") ?: "Jam error"))
                    }
                    "closed" -> {
                        _events.tryEmit(JamEvent.Info("closed"))
                        leave()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                heartbeat?.cancel()
                socket = null
                if (!scheduleReconnect()) _state.value = JamState(error = "connect")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                heartbeat?.cancel()
                socket = null
                if (!scheduleReconnect()) _state.value = JamState()
            }
        })
    }

    private var reconnectAttempt = 0

    /**
     * A guest whose connection dropped re-joins the same room with exponential backoff
     * (1, 2, 4, 8, 16 s), then gives up. The host's room closes on the server when it drops, so a
     * host is told to start a new session instead.
     */
    private fun scheduleReconnect(): Boolean {
        val server = lastServer
        val room = lastRoom
        if (userLeft || server == null || room == null || _state.value.status == "host" || reconnectAttempt >= MAX_RECONNECTS) {
            reconnectAttempt = 0
            return false
        }
        val wait = com.spotifree.app.util.backoffDelayMs(reconnectAttempt++, initialMs = 1_000, maxMs = 16_000)
        _state.value = _state.value.copy(status = "connecting", error = null)
        reconnectJob = scope.launch {
            delay(wait)
            if (!userLeft) {
                connect(server) {
                    send(buildJsonObject { put("type", "join"); put("room", room); put("name", lastName) }.toString())
                }
            }
        }
        return true
    }

    private fun trackJson(t: Track) = buildJsonObject {
        put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album); put("duration", t.durationSec)
        t.artworkUrl?.let { put("coverUrl", it) }; t.youtubeId?.let { put("youtubeId", it) }
    }

    private fun trackFrom(o: JsonObject): Track? {
        fun s(k: String) = o[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }
        val id = s("id") ?: return null
        return Track(id = id, title = s("title") ?: "", artist = s("artist") ?: "", album = s("album") ?: "",
            durationSec = o["duration"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0, coverUrl = s("coverUrl"), videoId = s("youtubeId"))
    }

    private fun playbackFrom(o: JsonObject): JamPlayback? {
        val track = (o["track"] as? JsonObject)?.let { trackFrom(it) } ?: return null
        return JamPlayback(
            track = track,
            isPlaying = o["isPlaying"]?.jsonPrimitive?.booleanOrNull ?: true,
            positionSec = o["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            sentAt = o["sentAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        )
    }

    private companion object {
        const val MAX_RECONNECTS = 5
    }
}
