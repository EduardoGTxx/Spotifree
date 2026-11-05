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

    fun isWifiOrUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Returns true if internet access is permitted under the current connection and mobile data preference. */
    fun isNetworkAllowed(context: Context, allowMobileData: Boolean): Boolean {
        if (!isOnline(context)) return false
        if (allowMobileData) return true
        return isWifiOrUnmetered(context)
    }
}
