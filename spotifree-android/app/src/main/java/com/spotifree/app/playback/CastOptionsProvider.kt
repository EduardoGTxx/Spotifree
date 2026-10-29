package com.spotifree.app.playback

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.spotifree.app.AppGraph

/** Cast framework configuration: the Default Media Receiver unless a custom one is set. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val custom = runCatchingCancellable { AppGraph.get(context).settings.current.castReceiverAppId }.getOrNull()
        val appId = custom?.trim()?.takeIf { it.isNotEmpty() } ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
