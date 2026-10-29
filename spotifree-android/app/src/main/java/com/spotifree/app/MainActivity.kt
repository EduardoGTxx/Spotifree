package com.spotifree.app

import com.spotifree.app.util.runCatchingCancellable

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spotifree.app.ui.SpotifreeRoot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    /** Intents that arrive while the UI is running (notification tap, shared link, voice search). */
    val intents = MutableSharedFlow<Intent>(replay = 1, extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark UI: light status/navigation bar icons regardless of the system theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        graph.player.connect()
        intent?.let { intents.tryEmit(it) }
        setContent { SpotifreeRoot(activity = this) }
    }

    override fun onStop() {
        super.onStop()
        // "Al salir" automatic backup
        if (isFinishing) {
            val graph = AppGraph.get(this)
            if (graph.settings.current.autoBackupFrequency == "on_exit") {
                graph.scope.launch { runCatchingCancellable { graph.backups.runAutoBackupIfDue(graph.account.account.value, "exit") } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { intents.emit(intent) }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.spotifree.app.OPEN_PLAYER"
    }
}
