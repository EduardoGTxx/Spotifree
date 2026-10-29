package com.spotifree.app.data.recognition

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

data class RecognizedSong(val title: String, val artist: String, val album: String, val coverUrl: String?)

class RecognitionException(val code: String, message: String) : Exception(message)

/**
 * "¿Qué suena?": records a short microphone clip and asks AudD or ACRCloud with the user's own key
 * (same providers and key formats as the previous app).
 */
class Recognizer(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Records [durationMs] of audio (AAC in MP4). [onLevel] receives 0..1 for the animation. */
    suspend fun record(durationMs: Long = 8000, onLevel: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "recognition.m4a")
        val recorder = MediaRecorder(context)
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            recorder.release()
            throw RecognitionException("permission", e.message ?: "mic")
        }
        try {
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end && coroutineContext.isActive) {
                delay(100)
                onLevel((recorder.maxAmplitude / 20000f).coerceIn(0f, 1f))
            }
        } finally {
            runCatchingCancellable { recorder.stop() }
            recorder.release()
        }
        file
    }

    suspend fun identify(file: File, provider: String, apiKey: String): RecognizedSong? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw RecognitionException("config", "missing key")
        if (provider == "acrcloud") acr(file, apiKey) else audd(file, apiKey)
    }

    private fun audd(file: File, key: String): RecognizedSong? {
        val body = FormBody.Builder()
            .add("api_token", key)
            .add("audio", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .add("return", "spotify,apple_music")
            .build()
        val text = http.newCall(Request.Builder().url("https://api.audd.io/").post(body).build()).execute().use { it.body?.string().orEmpty() }
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        if (o.s("status") == "error") {
            val msg = ((o["error"] as? JsonObject)?.s("error_message")) ?: "AudD error"
            throw RecognitionException("provider", msg)
        }
        val r = o["result"] as? JsonObject ?: return null
        val spotifyCover = (((r["spotify"] as? JsonObject)?.get("album") as? JsonObject)?.get("images") as? JsonArray)
            ?.firstOrNull()?.let { (it as? JsonObject)?.s("url") }
        val appleCover = ((r["apple_music"] as? JsonObject)?.get("artwork") as? JsonObject)?.s("url")?.replace("{w}x{h}", "600x600")
        return RecognizedSong(r.s("title") ?: return null, r.s("artist").orEmpty(), r.s("album").orEmpty(), spotifyCover ?: appleCover)
    }

    private fun acr(file: File, key: String): RecognizedSong? {
        val parts = key.split('|').map { it.trim() }
        if (parts.size < 3 || parts.any { it.isEmpty() }) throw RecognitionException("config", "host|access_key|access_secret")
        val (host, access, secret) = parts
        val ts = (System.currentTimeMillis() / 1000).toString()
        val toSign = listOf("POST", "/v1/identify", access, "audio", "1", ts).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
        val signature = Base64.encodeToString(mac.doFinal(toSign.toByteArray()), Base64.NO_WRAP)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("sample", "sample.m4a", file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("sample_bytes", file.length().toString())
            .addFormDataPart("access_key", access)
            .addFormDataPart("data_type", "audio")
            .addFormDataPart("signature_version", "1")
            .addFormDataPart("signature", signature)
            .addFormDataPart("timestamp", ts)
            .build()
        val url = "https://${host.removePrefix("https://").trimEnd('/')}/v1/identify"
        val text = http.newCall(Request.Builder().url(url).post(body).build()).execute().use { it.body?.string().orEmpty() }
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        val status = o["status"] as? JsonObject
        val code = status?.s("code")
        if (code == "1001") return null
        if (code != "0") throw RecognitionException("provider", status?.s("msg") ?: "ACRCloud error")
        val m = ((o["metadata"] as? JsonObject)?.get("music") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val artist = (m["artists"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("name") }?.joinToString(", ").orEmpty()
        val album = (m["album"] as? JsonObject)?.s("name").orEmpty()
        return RecognizedSong(m.s("title") ?: return null, artist, album, null)
    }

    private fun JsonObject.s(k: String): String? = this[k]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull }
}
