package com.spotifree.app.ui.settings

import com.spotifree.app.util.runCatchingCancellable

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.BuildConfig
import com.spotifree.app.data.drive.DriveBackup
import com.spotifree.app.data.drive.DriveResult
import com.spotifree.app.data.importer.AutoBackupWorker
import com.spotifree.app.data.prefs.AppSettings
import com.spotifree.app.data.update.AppUpdate
import com.spotifree.app.data.update.UpdateCheck
import com.spotifree.app.ui.BlockedRoute
import com.spotifree.app.ui.DownloadsManagerRoute
import com.spotifree.app.ui.LocalActions
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.RewindRoute
import com.spotifree.app.ui.SpotifyImportRoute
import com.spotifree.app.ui.StatsRoute
import com.spotifree.app.ui.YouTubeSignInRoute
import com.spotifree.app.ui.common.Artwork
import com.spotifree.app.ui.common.ConfirmDialog
import com.spotifree.app.ui.detail.DetailTopBar
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.Strings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.ACCENT_PRESETS
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import com.spotifree.app.ui.theme.parseColor
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalSpotifreeColors.current
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    val account by graph.account.account.collectAsStateWithLifecycle()
    val usedBytes by remember { graph.downloads.observeTotalBytes() }.collectAsState(initial = 0L)
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(f) }

    var handleDialog by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var keyDialog by remember { mutableStateOf(false) }
    var jamDialog by remember { mutableStateOf(false) }
    var castDialog by remember { mutableStateOf(false) }
    var pendingDrive by remember { mutableStateOf<String?>(null) } // "upload" | "download"

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatchingCancellable { graph.backups.writeTo(uri, graph.backups.export(account)) }
                .onSuccess { graph.messages.post(strings["backupDownloaded"]) }
                .onFailure { graph.messages.post(strings.f("backupFailed", it.message ?: ""), isError = true) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    val driveRunner = remember { arrayOfNulls<(String) -> Unit>(1) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val kind = pendingDrive
        pendingDrive = null
        if (res.resultCode == android.app.Activity.RESULT_OK && kind != null) driveRunner[0]?.invoke(kind)
    }

    fun runDrive(kind: String) {
        scope.launch {
            val drive = DriveBackup(context, graph)
            val r = if (kind == "upload") drive.upload() else drive.download()
            when (r) {
                is DriveResult.NeedsConsent -> {
                    pendingDrive = kind
                    consentLauncher.launch(IntentSenderRequest.Builder(r.intent.intentSender).build())
                }
                is DriveResult.Saved -> graph.messages.post(strings["driveSaved"])
                is DriveResult.Restored -> {
                    val sum = graph.backups.restore(r.json)
                    graph.messages.post(strings.f("restoreSummary", sum.playlists, sum.liked, sum.tracks))
                }
                DriveResult.NotFound -> graph.messages.post(strings["driveNotFound"], isError = true)
                is DriveResult.Failed -> graph.messages.post(strings.f("driveFailed", r.message), isError = true)
            }
        }
    }
    driveRunner[0] = { kind -> runDrive(kind) }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(t("settingsTitle"), onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            // ---------------------------------------------------------- account
            item {
                SettingsHeader(t("accountSection"))
                val acc = account
                if (acc != null) {
                    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(acc.avatarUrl.ifBlank { null }, size = 48.dp, shape = CircleShape, placeholder = Icons.Default.AccountCircle)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(acc.name.ifBlank { acc.handle })
                            Text(listOf(acc.email, acc.handle).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { t("connectedAs") },
                                color = c.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            Text(if (acc.cookie.isNotBlank()) t("ytSignedIn") else t("ytPublicOnly"), color = c.accent, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    }
                    SettingItem(t("disconnectGoogle"), icon = Icons.Default.Logout) { graph.account.disconnect() }
                } else {
                    SettingItem(t("connectGoogle"), t("googleSyncDesc"), Icons.Default.AccountCircle) { actions.nav.navigate(YouTubeSignInRoute) }
                    SettingItem(t("linkByHandle"), t("linkByHandleDesc"), Icons.Default.Link) { handleDialog = true }
                }
            }
            // ---------------------------------------------------------- language
            item {
                SettingsHeader(t("languageSection"))
                SettingChoice(t("languageLabel"), null, Strings.LANGUAGES, s.language) { v -> set { it.copy(language = v) } }
            }
            // ---------------------------------------------------------- appearance
            item {
                SettingsHeader(t("appearanceSection"))
                Text(t("accentColor"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ACCENT_PRESETS) { hex ->
                        val selected = hex.equals(s.accentColor, true)
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(parseColor(hex))
                                .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { set { it.copy(accentColor = hex) } },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Default.Check, null, tint = Color.Black) }
                    }
                }
                SettingSwitch(t("amoledLabel"), t("amoledDesc"), s.amoledTheme) { v -> set { it.copy(amoledTheme = v) } }
            }
            // ---------------------------------------------------------- playback
            item {
                SettingsHeader(t("playbackSection"))
                val offLabel = t("crossfadeOff")
                SettingSlider(t("crossfadeLabel"), { if (it < 0.5f) offLabel else "${it.toInt()} s" }, s.crossfadeSec.toFloat(), 0f..12f, 11,
                    t("crossfadeDesc")) { v -> set { it.copy(crossfadeSec = v.toInt()) } }
                SettingSwitch(t("volumeNormalizationLabel"), t("volumeNormalizationDesc"), s.volumeNormalization) { v -> set { it.copy(volumeNormalization = v) } }
                SettingSwitch(t("autoplayRadioLabel"), t("autoplayRadioDesc"), s.autoplayRadio) { v -> set { it.copy(autoplayRadio = v) } }
                SettingSwitch(t("skipSilenceLabel"), t("skipSilenceDesc"), s.skipSilence) { v -> set { it.copy(skipSilence = v) } }
                SettingSwitch(t("autoPauseNoisyLabel"), t("autoPauseNoisyDesc"), s.autoPauseOnNoisy) { v -> set { it.copy(autoPauseOnNoisy = v) } }
                SettingSwitch(t("autoResumeBtLabel"), t("autoResumeBtDesc"), s.autoResumeOnBluetooth) { v -> set { it.copy(autoResumeOnBluetooth = v) } }
                SettingSwitch(t("smartPrefetchLabel"), t("smartPrefetchDesc"), s.smartPrefetch) { v -> set { it.copy(smartPrefetch = v) } }
            }
            // ---------------------------------------------------------- lyrics
            item {
                SettingsHeader(t("lyrics"))
                SettingSwitch(t("karaokeLabel"), t("karaokeDesc"), s.lyricsKaraoke) { v -> set { it.copy(lyricsKaraoke = v) } }
                SettingSwitch(t("romanizationLabel"), t("romanizationDesc"), s.lyricsRomanization) { v -> set { it.copy(lyricsRomanization = v) } }
                SettingChoice(t("lyricsFontSize"), null, listOf("sm" to "S", "md" to "M", "lg" to "L", "xl" to "XL"), s.lyricsFontSize) { v -> set { it.copy(lyricsFontSize = v) } }
                SettingItem(t("clearLyricsCache"), icon = Icons.Default.DeleteSweep) {
                    scope.launch { graph.lyrics.clearCache(); graph.messages.post(strings["done"]) }
                }
            }
            // ---------------------------------------------------------- downloads
            item {
                SettingsHeader(t("offlineSection"))
                SettingSwitch(t("offlineModeLabel"), t("offlineModeDesc"), s.offlineMode) { v -> set { it.copy(offlineMode = v) } }
                SettingChoice(t("offlineQualityLabel"), t("qualityDesc"),
                    listOf("saving" to t("qualitySaving"), "normal" to t("qualityNormal"), "high" to t("qualityHigh")), s.audioQuality) { v -> set { it.copy(audioQuality = v) } }
                SettingSwitch(t("wifiOnlyLabel"), null, s.downloadOnlyWifi) { v -> set { it.copy(downloadOnlyWifi = v) } }
                SettingSwitch(t("autoCacheLabel"), t("autoCacheDesc"), s.autoCacheOnPlay) { v -> set { it.copy(autoCacheOnPlay = v) } }
                SettingSwitch(t("autoDownloadLikedLabel"), t("autoDownloadLikedDesc"), s.autoDownloadLiked) { v -> set { it.copy(autoDownloadLiked = v) } }
                SettingSlider(t("cacheLimitLabel"), { mb -> if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "${mb.toInt()} MB" }, s.cacheSizeMb.toFloat(), 256f..8192f, 30,
                    t("cacheLimitDesc")) { v -> set { it.copy(cacheSizeMb = (v / 256).toInt() * 256) } }
                SettingItem(t("storageUsedLabel"), "%.1f MB".format((usedBytes ?: 0L) / 1048576.0), Icons.Default.Storage) { actions.nav.navigate(DownloadsManagerRoute) }
                SettingItem(t("clearOfflineData"), icon = Icons.Default.DeleteSweep) { confirmClear = true }
            }
            // ---------------------------------------------------------- library
            item {
                SettingsHeader(t("librarySection"))
                SettingItem(t("importSpotifyData"), t("importSpotifyDataDesc"), Icons.Default.CloudDownload) { actions.nav.navigate(SpotifyImportRoute) }
                SettingItem(t("hiddenContent"), t("hiddenContentDesc"), Icons.Default.Block) { actions.nav.navigate(BlockedRoute) }
                SettingItem(t("statsTitle"), null, Icons.Default.BarChart) { actions.nav.navigate(StatsRoute) }
                SettingItem(t("rewindTitle"), t("rewindDesc"), Icons.Default.BarChart) { actions.nav.navigate(RewindRoute) }
            }
            // ---------------------------------------------------------- backups
            item {
                SettingsHeader(t("backupSection"))
                Text(t("backupDesc"), color = c.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                SettingItem(t("exportBackup"), null, Icons.Default.FileDownload) {
                    exportLauncher.launch("Spotifree_Backup_${java.time.LocalDate.now()}.json")
                }
                SettingItem(t("importBackup"), null, Icons.Default.FileUpload) { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                SettingChoice(t("autoBackupLabel"), t("autoBackupDesc"),
                    listOf("off" to t("off"), "daily" to t("daily"), "weekly" to t("weekly"), "on_exit" to t("onExit")), s.autoBackupFrequency) { v ->
                    set { it.copy(autoBackupFrequency = v) }
                    AutoBackupWorker.schedule(context, v)
                }
                if (s.lastAutoBackupAt > 0) Text(t("lastBackup", DateFormat.getDateTimeInstance().format(Date(s.lastAutoBackupAt))),
                    color = c.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                SettingItem(t("driveBackupNow"), if (s.lastDriveBackupAt > 0) t("lastBackup", DateFormat.getDateTimeInstance().format(Date(s.lastDriveBackupAt))) else t("driveDesc"),
                    Icons.Default.CloudUpload) { runDrive("upload") }
                SettingItem(t("driveRestore"), null, Icons.Default.Backup) { runDrive("download") }
                SettingSwitch(t("driveAuto"), t("driveAutoDesc"), s.driveAutoBackup) { v -> set { it.copy(driveAutoBackup = v) } }
            }
            // ---------------------------------------------------------- integrations
            item {
                SettingsHeader(t("integrationsSection"))
                SettingChoice(t("recognitionProvider"), null, listOf("audd" to "AudD", "acrcloud" to "ACRCloud"), s.recognitionProvider) { v -> set { it.copy(recognitionProvider = v) } }
                SettingItem(t("recognitionKey"), if (s.recognitionApiKey.isBlank()) t("notSet") else "••••" + s.recognitionApiKey.takeLast(4)) { keyDialog = true }
                SettingItem(t("jamServer"), s.jamServerUrl.ifBlank { t("notSet") }) { jamDialog = true }
                SettingItem(t("castReceiver"), s.castReceiverAppId.ifBlank { t("castReceiverDefault") }) { castDialog = true }
            }
            // ---------------------------------------------------------- TGym
            item {
                SettingsHeader(t("tgymSection"))
                SettingSwitch(t("enableTGymLabel"), t("enableTGymDesc"), s.enableTGym) { v -> set { it.copy(enableTGym = v) } }
                SettingSwitch(t("tgymLiveSyncLabel"), t("tgymLiveSyncDesc"), s.tgymLiveSync, enabled = s.enableTGym) { v -> set { it.copy(tgymLiveSync = v) } }
                SettingSlider(t("duckingLevel"), { "${(it * 100).toInt()} %" }, s.tgymDuckingLevel, 0.05f..0.8f, 14) { v -> set { it.copy(tgymDuckingLevel = v) } }
                SettingSlider(t("restDurationLabel"), { "${it.toInt()} s" }, s.workoutRestSec.toFloat(), 15f..300f, 18) { v -> set { it.copy(workoutRestSec = (v / 15).toInt() * 15) } }
            }
            // ---------------------------------------------------------- updates & about
            item {
                SettingsHeader(t("updatesSection"))
                SettingItem(t("currentVersionLabel"), "v${BuildConfig.VERSION_NAME}", Icons.Default.Info)
                SettingItem(if (checking) t("checkingUpdates") else t("checkForUpdatesBtn"), t("privateRepoNotice"), Icons.Default.SystemUpdate, enabled = !checking) {
                    checking = true
                    scope.launch {
                        when (val r = graph.updates.check()) {
                            is UpdateCheck.Available -> update = r.update
                            UpdateCheck.UpToDate -> graph.messages.post(strings["upToDate"])
                            is UpdateCheck.Failed -> graph.messages.post(strings.f("updateCheckFailed", r.reason), isError = true)
                        }
                        checking = false
                    }
                }
                SettingsHeader(t("aboutSection"))
                Text(t("aboutNative"), color = c.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                SettingItem("NewPipe Extractor (GPLv3)", "github.com/TeamNewPipe/NewPipeExtractor") {
                    runCatchingCancellable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TeamNewPipe/NewPipeExtractor"))) }
                }
            }
        }
    }

    if (handleDialog) TextInputDialog(t("linkByHandle"), "@usuario", "", onDismiss = { handleDialog = false }) { h ->
        handleDialog = false
        scope.launch {
            runCatchingCancellable { graph.account.connectByHandle(h) }
                .onSuccess { (acc, lists) -> graph.messages.post(strings.f("connectedHandle", acc.name, lists.size)) }
                .onFailure { graph.messages.post(strings["channelNotFound"], isError = true) }
        }
    }
    if (keyDialog) TextInputDialog(t("recognitionKey"), if (s.recognitionProvider == "acrcloud") "host|access_key|access_secret" else "api_token",
        s.recognitionApiKey, onDismiss = { keyDialog = false }) { v -> keyDialog = false; set { it.copy(recognitionApiKey = v.trim()) } }
    if (jamDialog) TextInputDialog(t("jamServer"), "wss://mi-servidor.example/jam", s.jamServerUrl, onDismiss = { jamDialog = false }) { v ->
        jamDialog = false; set { it.copy(jamServerUrl = v.trim()) }
    }
    if (castDialog) TextInputDialog(t("castReceiver"), "CC1AD845", s.castReceiverAppId, onDismiss = { castDialog = false }) { v ->
        castDialog = false; set { it.copy(castReceiverAppId = v.trim()) }
    }
    restoreUri?.let { uri ->
        ConfirmDialog(t("importBackup"), t("restoreConfirm"), t("restore"), onDismiss = { restoreUri = null }) {
            scope.launch {
                runCatchingCancellable {
                    val sum = graph.backups.restore(graph.backups.parse(graph.backups.readFrom(uri)))
                    graph.messages.post(strings.f("restoreSummary", sum.playlists, sum.liked, sum.tracks))
                }.onFailure { graph.messages.post(strings["restoreFailed"], isError = true) }
            }
        }
    }
    if (confirmClear) ConfirmDialog(t("clearOfflineData"), t("clearOfflineConfirm"), t("delete"), onDismiss = { confirmClear = false }) {
        scope.launch { graph.downloads.clearAll(); graph.messages.post(strings["clearedSuccess"]) }
    }
    update?.let { u -> UpdateDialog(u, onDismiss = { update = null }) }
}

@Composable
fun TextInputDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(t("save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
