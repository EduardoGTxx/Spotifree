package com.spotifree.app.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spotifree.app.AppGraph
import kotlinx.coroutines.launch

/**
 * Receives TGym's rest and workout events (see [TGymBridge] for the contract). Rest periods lower
 * the music to the "ducking" level; the playback service restores it when the rest ends (or when
 * its end time passes, if TGym never sends REST_END) and plays a short chime.
 */
class TGymReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.get(context)
        val s = graph.settings.current
        if (!s.enableTGym) return
        val event = TGymBridge.parse(intent) ?: return
        val events = graph.playbackEvents
        when (event) {
            is TGymEvent.RestStart -> if (s.tgymLiveSync) {
                events.restEndsAt.value = event.endsAt
                events.duck.value = true
            }
            TGymEvent.RestEnd -> if (events.duck.value) {
                events.restEndsAt.value = 0L
                events.duck.value = false
                events.chime.tryEmit(Unit)
            }
            is TGymEvent.WorkoutFinish -> {
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        graph.settings.update { WorkoutStreak.mark(it, event.date) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
