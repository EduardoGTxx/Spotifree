package com.spotifree.app.ui.migration

import com.spotifree.app.util.runCatchingCancellable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.data.importer.LegacyMigration
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import kotlinx.coroutines.launch

/** First launch after updating from the previous version: moves its data into the new app. */
@Composable
fun MigrationScreen(migration: LegacyMigration, onDone: () -> Unit) {
    val c = LocalSpotifreeColors.current
    val scope = rememberCoroutineScope()
    val progress by migration.progress.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) { runCatchingCancellable { migration.run() } }

    Column(
        Modifier.fillMaxSize().background(c.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val summary = progress.summary
        when {
            summary != null -> {
                Icon(Icons.Default.CheckCircle, null, tint = c.accent, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text(t("migrationDone"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    t("migrationSummary", summary.playlists, summary.liked, summary.songs, summary.history, summary.downloads),
                    color = c.textSecondary, textAlign = TextAlign.Center,
                )
                if (summary.account) Text(t("migrationAccount"), color = c.textSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(t("continue")) }
            }
            progress.error != null -> {
                Text(t("migrationFailed"), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(progress.error.orEmpty(), color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
                Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text(t("retry")) }
                OutlinedButton(onClick = { scope.launch { migration.markDone(); onDone() } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(t("migrationSkip"))
                }
            }
            else -> {
                Icon(Icons.Default.SystemUpdateAlt, null, tint = c.accent, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text(t("migrationTitle"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(t("migrationDesc"), color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
                Spacer(Modifier.height(16.dp))
                val step = when (progress.step) {
                    "library" -> t("migrationStepLibrary")
                    "history" -> t("migrationStepHistory")
                    "downloads" -> t("migrationStepDownloads")
                    "saving" -> t("migrationStepSaving")
                    else -> progress.step
                }
                Text(step)
                if (progress.total > 0) {
                    LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${progress.done} / ${progress.total}", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                } else {
                    CircularProgressIndicator(Modifier.padding(top = 12.dp), color = c.accent)
                }
            }
        }
    }
}
