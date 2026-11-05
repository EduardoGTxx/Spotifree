package com.spotifree.app.playback

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spotifree.app.AppGraph

/** Optional: resume when headphones or the car connect over Bluetooth (setting "autoResumeOnBluetooth"). */
class BluetoothResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        if (!shouldResume(intent.action, state)) return
        val graph = AppGraph.get(context)
        if (!graph.settings.current.autoResumeOnBluetooth) return
        val p = graph.player
        if (!p.state.value.connected) p.connect()
        p.resumeWhenReady()
    }

    companion object {
        internal fun shouldResume(action: String?, state: Int): Boolean =
            action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                ((action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                    action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) &&
                    state == BluetoothProfile.STATE_CONNECTED)
    }
}
