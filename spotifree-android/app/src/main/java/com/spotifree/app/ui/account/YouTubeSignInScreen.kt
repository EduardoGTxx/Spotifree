package com.spotifree.app.ui.account

import com.spotifree.app.util.runCatchingCancellable

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifree.app.ui.LocalGraph
import com.spotifree.app.ui.common.EmptyState
import com.spotifree.app.ui.detail.DetailTopBar
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import kotlinx.coroutines.launch

/**
 * Google sign-in for YouTube Music inside a WebView (same approach as the previous app): once
 * music.youtube.com has a session the cookies are kept to read the user's playlists.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val appSettings by graph.settings.settings.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val cookies = remember { CookieManager.getInstance() }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(t("connectGoogle"), onBack)
        if (appSettings.offlineMode) {
            EmptyState(Icons.Default.CloudOff, t("offlineModeOn"))
            return@Column
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    settings.blockNetworkLoads = appSettings.offlineMode
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google rejects embedded WebViews that announce themselves as such
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            if (done) return
                            val host = android.net.Uri.parse(url).host.orEmpty()
                            if (!host.endsWith("music.youtube.com")) return
                            val cookie = cookies.getCookie("https://music.youtube.com").orEmpty()
                            if (cookie.contains("SAPISID") || cookie.contains("LOGIN_INFO")) {
                                done = true
                                cookies.flush()
                                scope.launch {
                                    runCatchingCancellable { graph.account.completeSignIn(cookie) }
                                        .onSuccess { graph.messages.post(strings.f("connectedAsN", it.name)); onBack() }
                                        .onFailure { done = false; graph.messages.post(strings["signInFailed"], isError = true) }
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com")
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
