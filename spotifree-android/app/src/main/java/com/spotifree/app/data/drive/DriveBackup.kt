package com.spotifree.app.data.drive

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.spotifree.app.AppGraph
import com.spotifree.app.data.importer.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface DriveResult {
    data class Saved(val modifiedTime: String?) : DriveResult
    data class Restored(val json: JsonObject) : DriveResult
    /** The user must approve access: launch this with an IntentSender launcher, then retry. */
    data class NeedsConsent(val intent: PendingIntent) : DriveResult
    data object NotFound : DriveResult
    data class Failed(val message: String) : DriveResult
}

/**
 * Google Drive backup (REST v3, scope drive.file: the app only sees the file it created).
 * Same file name as the previous app: "Spotifree_Backup.json".
 * Requires an OAuth client of type Android (package + SHA-1 of the signing key) in Google Cloud.
 */
class DriveBackup(private val context: Context, private val graph: AppGraph) {
    private val json = Json { ignoreUnknownKeys = true }

    private sealed interface Token {
        data class Ok(val token: String) : Token
        data class Consent(val intent: PendingIntent) : Token
        data class Error(val message: String) : Token
    }

    private suspend fun token(interactive: Boolean): Token {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val result: AuthorizationResult = try {
            suspendCancellableCoroutine { cont ->
                Identity.getAuthorizationClient(context).authorize(request)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
            throw ec
        } catch (e: Exception) {
            return Token.Error(e.message ?: "auth")
        }
        val token = result.accessToken
        if (token != null) return Token.Ok(token)
        val pi = result.pendingIntent
        return if (pi != null && interactive) Token.Consent(pi) else Token.Error("needs_consent")
    }

    suspend fun upload(interactive: Boolean = true): DriveResult = withContext(Dispatchers.IO) {
        val token = when (val t = token(interactive)) {
            is Token.Ok -> t.token
            is Token.Consent -> return@withContext DriveResult.NeedsConsent(t.intent)
            is Token.Error -> return@withContext DriveResult.Failed(t.message)
        }
        try {
            val content = BackupManager(context, graph.db, graph.settings).export(graph.account.account.value)
            var id = findFile(token)
            if (id == null) {
                val meta = """{"name":"$NAME","mimeType":"application/json"}"""
                val created = call(token, Request.Builder().url("$API?fields=id").post(meta.toRequestBody(JSON_TYPE)))
                id = (json.parseToJsonElement(created) as JsonObject)["id"]?.jsonPrimitive?.contentOrNull ?: return@withContext DriveResult.Failed("create")
            }
            val res = call(token, Request.Builder().url("$UPLOAD/$id?uploadType=media&fields=id,modifiedTime").patch(content.toRequestBody(JSON_TYPE)))
            val modified = (json.parseToJsonElement(res) as JsonObject)["modifiedTime"]?.jsonPrimitive?.contentOrNull
            graph.settings.update { it.copy(lastDriveBackupAt = System.currentTimeMillis()) }
            DriveResult.Saved(modified)
        } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
            throw ec
        } catch (e: Exception) {
            DriveResult.Failed(e.message ?: "error")
        }
    }

    suspend fun download(interactive: Boolean = true): DriveResult = withContext(Dispatchers.IO) {
        val token = when (val t = token(interactive)) {
            is Token.Ok -> t.token
            is Token.Consent -> return@withContext DriveResult.NeedsConsent(t.intent)
            is Token.Error -> return@withContext DriveResult.Failed(t.message)
        }
        try {
            val id = findFile(token) ?: return@withContext DriveResult.NotFound
            val text = call(token, Request.Builder().url("$API/$id?alt=media").get())
            DriveResult.Restored(BackupManager(context, graph.db, graph.settings).parse(text))
        } catch (ec: kotlin.coroutines.cancellation.CancellationException) {
            throw ec
        } catch (e: Exception) {
            DriveResult.Failed(e.message ?: "error")
        }
    }

    private fun findFile(token: String): String? {
        val q = URLEncoder.encode("name='$NAME' and trashed=false", "UTF-8")
        val body = call(token, Request.Builder().url("$API?q=$q&spaces=drive&orderBy=modifiedTime%20desc&fields=files(id,modifiedTime)").get())
        val files = (json.parseToJsonElement(body) as JsonObject)["files"] as? JsonArray
        return (files?.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
    }

    private fun call(token: String, builder: Request.Builder): String {
        graph.http.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("Drive HTTP ${r.code}")
            return body
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val NAME = "Spotifree_Backup.json"
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
