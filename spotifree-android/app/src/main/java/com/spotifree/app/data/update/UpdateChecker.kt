package com.spotifree.app.data.update

import com.spotifree.app.util.runCatchingCancellable

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.spotifree.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

data class AppUpdate(
    val version: String,
    val title: String,
    val notes: List<String>,
    val apkUrl: String,
    val sha256: String?,
)

sealed interface UpdateCheck {
    data class Available(val update: AppUpdate) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val reason: String) : UpdateCheck
}

/** In-app updates from the project's GitHub releases (same source as the previous app). */
class UpdateChecker(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val latest = runCatchingCancellable { fromReleases() }.getOrNull() ?: runCatchingCancellable { fromManifest() }.getOrNull()
            ?: return@withContext UpdateCheck.Failed(lastReason)
        if (compare(BuildConfig.VERSION_NAME, latest.version) < 0) UpdateCheck.Available(latest) else UpdateCheck.UpToDate
    }

    private var lastReason = "network"

    private fun fromReleases(): AppUpdate? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github.v3+json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) { lastReason = "repo_not_found"; return null }
            if (r.code == 403) { lastReason = "rate_limit"; return null }
            if (!r.isSuccessful) return null
            val o = json.parseToJsonElement(r.body?.string() ?: return null) as JsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val version = tag.removePrefix("v")
            val body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                ?: "https://github.com/$REPO/releases/download/$tag/Spotifree.apk"
            return AppUpdate(
                version = version,
                title = o["name"]?.jsonPrimitive?.contentOrNull ?: "Spotifree v$version",
                notes = body.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("*") }.map { it.trimStart('-', '*', ' ') },
                apkUrl = apk,
                sha256 = Regex("""sha-?256[:\s=]+([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1),
            )
        }
    }

    private fun fromManifest(): AppUpdate? {
        // Published next to the APK by the release workflow
        val req = Request.Builder().url("https://github.com/$REPO/releases/latest/download/update-manifest.json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = json.parseToJsonElement(r.body?.string() ?: return null) as JsonObject
            val version = o["version"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return null
            return AppUpdate(
                version = version,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: "Spotifree v$version",
                notes = (o["notes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                apkUrl = o["apkUrl"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$REPO/releases/download/v$version/Spotifree.apk",
                sha256 = o["sha256"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** Downloads the APK (verifying SHA-256 when published) and hands it to the system installer. */
    suspend fun downloadAndInstall(update: AppUpdate, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("empty")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (update.sha256 != null && !sha.equals(update.sha256, true)) {
                file.delete()
                throw SecurityException("checksum")
            }
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("spotifree.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    companion object {
        const val REPO = "EduardoGTxx/Spotifree"

        fun compare(a: String, b: String): Int {
            fun parts(v: String) = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""").find(v.trim())?.groupValues
            val av = parts(a) ?: return 0
            val bv = parts(b) ?: return 0
            for (i in 1..3) {
                val x = av[i].toInt()
                val y = bv[i].toInt()
                if (x != y) return x.compareTo(y)
            }
            val ap = av[4]
            val bp = bv[4]
            if (ap == bp) return 0
            if (ap.isEmpty()) return 1
            if (bp.isEmpty()) return -1
            return ap.compareTo(bp)
        }
    }
}
