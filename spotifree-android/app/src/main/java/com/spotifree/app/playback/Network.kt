package com.spotifree.app.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.spotifree.app.data.prefs.AppSettings
import java.io.IOException

/** Thrown whenever any HTTP, DNS, WebSocket, or streaming call is attempted while Offline Mode is enabled. */
class OfflineModeException : IOException("Offline mode is enabled: all Wi-Fi and mobile data traffic is blocked")

enum class ConnectionKind { WIFI, MOBILE, NONE }

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

    fun isOnMobileData(context: Context): Boolean = currentConnection(context) == ConnectionKind.MOBILE

    fun currentConnection(context: Context): ConnectionKind {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ConnectionKind.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return ConnectionKind.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return ConnectionKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> ConnectionKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionKind.MOBILE
            else -> ConnectionKind.NONE
        }
    }

    /** Returns true if internet access is permitted under the current connection and mobile data preference. */
    fun isNetworkAllowed(context: Context, allowMobileData: Boolean): Boolean {
        if (!isOnline(context)) return false
        if (allowMobileData) return true
        return isWifiOrUnmetered(context)
    }
}

/**
 * Pure state-transition rules for automatic Offline Mode <-> Wi-Fi / Mobile Data handling:
 * - On mobile data with allowMobileData=false -> automatically enables offlineMode.
 * - On Wi-Fi transition (or startup on Wi-Fi) -> automatically disables offlineMode.
 * - User manually disables offlineMode while on mobile data -> automatically enables allowMobileData.
 * - User manually disables allowMobileData while on mobile data -> automatically enables offlineMode.
 */
object NetworkPolicy {
    fun onNetworkChange(
        current: AppSettings,
        previousKind: ConnectionKind?,
        newKind: ConnectionKind,
    ): AppSettings? = when (newKind) {
        ConnectionKind.MOBILE -> {
            if (!current.allowMobileData && !current.offlineMode) current.copy(offlineMode = true) else null
        }
        ConnectionKind.WIFI -> {
            if (previousKind != ConnectionKind.WIFI && current.offlineMode) current.copy(offlineMode = false) else null
        }
        ConnectionKind.NONE -> null
    }

    fun onManualOfflineToggle(
        current: AppSettings,
        enableOffline: Boolean,
        kind: ConnectionKind,
    ): AppSettings = if (!enableOffline && kind == ConnectionKind.MOBILE) {
        current.copy(offlineMode = false, allowMobileData = true)
    } else {
        current.copy(offlineMode = enableOffline)
    }

    fun onManualAllowMobileDataToggle(
        current: AppSettings,
        allowMobile: Boolean,
        kind: ConnectionKind,
    ): AppSettings = if (!allowMobile && kind == ConnectionKind.MOBILE) {
        current.copy(allowMobileData = false, offlineMode = true)
    } else {
        current.copy(allowMobileData = allowMobile)
    }
}
