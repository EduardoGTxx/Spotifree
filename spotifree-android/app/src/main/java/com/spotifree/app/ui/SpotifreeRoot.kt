package com.spotifree.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.AppGraph
import com.spotifree.app.MainActivity
import com.spotifree.app.data.importer.LegacyMigration
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.Strings
import com.spotifree.app.ui.migration.MigrationScreen
import com.spotifree.app.ui.theme.SpotifreeTheme

@Composable
fun SpotifreeRoot(activity: MainActivity) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val strings = remember(settings.language) { Strings.load(context, settings.language) }
    val migration = remember { LegacyMigration(context.applicationContext, graph) }
    var migrating by remember { mutableStateOf(migration.isNeeded()) }

    CompositionLocalProvider(LocalGraph provides graph, LocalStrings provides strings) {
        SpotifreeTheme(accentHex = settings.accentColor, amoled = settings.amoledTheme) {
            if (migrating) {
                MigrationScreen(migration, onDone = { migrating = false })
            } else {
                MainScreen(activity)
            }
        }
    }
}
