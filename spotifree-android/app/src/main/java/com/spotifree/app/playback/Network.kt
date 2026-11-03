package com.spotifree.app.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException

/** Thrown whenever any HTTP, DNS, WebSocket, or streaming call is attempted while Offline Mode is enabled. */
class OfflineModeException : IOException("Offline mode is enabled: all Wi-Fi and mobile data traffic is blocked")

object Network {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
