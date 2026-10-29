package com.spotifree.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spotifree.app.AppGraph

/** Optional: resume when headphones or the car connect over Bluetooth (setting "autoResumeOnBluetooth"). */
class BluetoothResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.get(context)
        if (!graph.settings.current.autoResumeOnBluetooth) return
        val p = graph.player
        if (!p.state.value.connected) p.connect()
        p.resumeWhenReady()
    }
}
