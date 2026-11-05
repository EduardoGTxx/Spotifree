package com.spotifree.app.ui

import com.spotifree.app.util.runCatchingCancellable

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import com.spotifree.app.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.spotifree.app.MainActivity
import com.spotifree.app.data.model.Track
import com.spotifree.app.data.remote.YouTubeIds
import com.spotifree.app.data.jam.JamEvent
import com.spotifree.app.data.jam.JamPlayback
import com.spotifree.app.ui.common.UiMessage
import com.spotifree.app.data.update.UpdateCheck
import com.spotifree.app.data.update.AppUpdate
import com.spotifree.app.playback.PlayerEvent
import com.spotifree.app.ui.account.YouTubeSignInScreen
import com.spotifree.app.ui.common.AddToPlaylistSheet
import com.spotifree.app.ui.common.TrackMenuSheet
import com.spotifree.app.ui.detail.ArtistScreen
import com.spotifree.app.ui.detail.CollectionScreen
import com.spotifree.app.ui.detail.PlaylistScreen
import com.spotifree.app.ui.home.HomeScreen
import com.spotifree.app.ui.i18n.LocalStrings
import com.spotifree.app.ui.i18n.t
import com.spotifree.app.ui.library.LibraryScreen
import com.spotifree.app.ui.player.MiniPlayer
import com.spotifree.app.ui.player.NowPlayingScreen
import com.spotifree.app.ui.search.SearchScreen
import com.spotifree.app.ui.settings.BlockedScreen
import com.spotifree.app.ui.settings.DownloadsManagerScreen
import com.spotifree.app.ui.settings.SettingsScreen
import com.spotifree.app.ui.settings.SpotifyImportScreen
import com.spotifree.app.ui.settings.UpdateDialog
import com.spotifree.app.ui.stats.RewindScreen
import com.spotifree.app.ui.stats.StatsScreen
import com.spotifree.app.ui.theme.LocalSpotifreeColors
import com.spotifree.app.playback.Network
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class Tab(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
fun MainScreen(activity: MainActivity) {
    val graph = LocalGraph.current
    val strings = LocalStrings.current
    val stringsState = rememberUpdatedState(strings)
    val context = LocalContext.current
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val c = LocalSpotifreeColors.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val playerState by graph.player.state.collectAsStateWithLifecycle()

    var showPlayer by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Pair<Track, MenuContext>?>(null) }
    var addTo by remember { mutableStateOf<List<Track>?>(null) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var dismissedInSession by remember { mutableStateOf<String?>(null) }

    val actions = remember(nav) {
        AppActions(
            graph = graph,
            scope = graph.scope,
            nav = nav,
            strings = { stringsState.value },
            showMenu = { t, ctx -> menu = t to ctx },
            showAddToPlaylist = { addTo = it },
            openPlayer = { showPlayer = true },
        )
    }

    // Notifications permission (Android 13+): needed for the playback and download notifications.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // App-wide messages
    LaunchedEffect(Unit) {
        graph.messages.flow.collect { m ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar(
                    message = m.text,
                    actionLabel = m.actionLabel,
                    duration = if (m.actionLabel != null || m.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
            }
        }
    }
    LaunchedEffect(Unit) {
        graph.player.events.collect { e ->
            if (e is PlayerEvent.Unplayable) graph.messages.post(strings.f("cannotPlay", e.title), isError = true)
        }
    }

    // "Escuchar juntos": the host shares its playback, guests follow it and suggest songs
    LaunchedEffect(Unit) {
        graph.jam.currentPlayback = {
            val st = graph.player.state.value
            st.current?.let { JamPlayback(it, st.isPlaying, graph.player.position.value / 1000.0, System.currentTimeMillis()) }
        }
        graph.jam.events.collect { e ->
            when (e) {
                is JamEvent.Follow -> {
                    val p = e.playback
                    val lagSec = ((System.currentTimeMillis() - p.sentAt) / 1000.0).coerceIn(0.0, 10.0)
                    val target = ((p.positionSec + if (p.isPlaying) lagSec else 0.0) * 1000).toLong()
                    val st = graph.player.state.value
                    if (st.current?.id != p.track.id) {
                        graph.player.playTracks(listOf(p.track))
                        graph.player.seekTo(target)
                    } else if (kotlin.math.abs(graph.player.position.value - target) > 3000) {
                        graph.player.seekTo(target)
                    }
                    if (p.isPlaying && !st.isPlaying) graph.player.play()
                    if (!p.isPlaying && st.isPlaying) graph.player.pause()
                }
                is JamEvent.Suggestion -> graph.messages.post(
                    UiMessage(strings.f("jamSuggested", e.from, e.track.title), actionLabel = strings["add"], long = true,
                        action = { graph.player.addToQueue(e.track) })
                )
                is JamEvent.Info -> graph.messages.post(if (e.message == "closed") strings["jamClosed"] else e.message)
            }
        }
    }

    // Automatic update check on launch, foreground resume, and network recovery
    fun triggerUpdateCheck() {
        if (graph.settings.current.offlineMode) return
        if (!Network.isNetworkAllowed(context, graph.settings.current.allowMobileData)) return
        scope.launch {
            val r = runCatchingCancellable { graph.updates.check() }.getOrNull()
            if (r is UpdateCheck.Available) {
                availableUpdate = r.update
                if (dismissedInSession != r.update.version) {
                    update = r.update
                }
            } else if (r is UpdateCheck.UpToDate) {
                availableUpdate = null
            }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        triggerUpdateCheck()
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                triggerUpdateCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        graph.settings.settings
            .map { it.offlineMode to it.allowMobileData }
            .distinctUntilChanged()
            .collect { (offline, allowMobile) ->
                if (!offline && Network.isNetworkAllowed(context, allowMobile)) {
                    triggerUpdateCheck()
                }
            }
    }

    // Automatic backup on exit / at startup when due
    LaunchedEffect(Unit) { runCatchingCancellable { graph.backups.runAutoBackupIfDue(graph.account.account.value, "startup") } }

    // Intents: notification tap, shared links, voice search
    LaunchedEffect(Unit) {
        activity.intents.collect { intent ->
            activity.intents.resetReplayCache()
            handleIntent(intent, actions, onOpenPlayer = { showPlayer = true })
        }
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }

    val tabs = buildList {
        add(Tab(HomeRoute, t("navHome"), Icons.Outlined.Home, Icons.Filled.Home))
        add(Tab(SearchRoute, t("navSearch"), Icons.Outlined.Search, Icons.Filled.Search))
        add(Tab(LibraryRoute, t("navLibrary"), Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic))
    }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination

    CompositionLocalProvider(LocalActions provides actions) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (c.isGlassy) {
                        Modifier.background(
                            Brush.verticalGradient(
                                colors = com.spotifree.app.ui.theme.glassyGradient(c.accent)
                            )
                        )
                    } else {
                        Modifier.background(c.background)
                    }
                )
        ) {
            if (c.isGlassy) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(c.accent.copy(alpha = 0.25f), Color.Transparent),
                            center = Offset(size.width * 0.15f, size.height * 0.82f),
                            radius = size.width * 0.90f
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 110.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        c.accent.copy(alpha = 0.45f),
                                        c.accent.copy(alpha = 0.20f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.35f),
                                        Color.White.copy(alpha = 0.08f)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_spotifree_logo),
                            contentDescription = "Spotifree",
                            modifier = Modifier.size(170.dp),
                            alpha = 0.35f
                        )
                    }
                }
            }
            Scaffold(
                containerColor = if (c.isGlassy) Color.Transparent else c.background,
                // Screens draw behind the status bar themselves (header gradients); see [Inset]
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    // Rewind is full screen, like stories
                    if (destination?.hasRoute(RewindRoute::class) != true) Column {
                        if (playerState.current != null) MiniPlayer(onOpen = { showPlayer = true })
                        BottomBar(tabs, selected = { tab -> destination?.hierarchy?.any { d -> d.hasRoute(tab.route::class) } == true }) { tab ->
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
                    composable<HomeRoute> { HomeScreen(availableUpdate = availableUpdate, onUpdateClick = { update = it }) }
                    composable<SearchRoute> { Inset { SearchScreen() } }
                    composable<LibraryRoute> { Inset { LibraryScreen() } }
                    composable<SettingsRoute> { Inset { SettingsScreen(onBack = { nav.popBackStack() }) } }
                    composable<StatsRoute> { Inset { StatsScreen(onBack = { nav.popBackStack() }) } }
                    composable<RewindRoute> { RewindScreen(onBack = { nav.popBackStack() }) }
                    composable<SpotifyImportRoute> { Inset { SpotifyImportScreen(onBack = { nav.popBackStack() }) } }
                    composable<YouTubeSignInRoute> { Inset { YouTubeSignInScreen(onBack = { nav.popBackStack() }) } }
                    composable<BlockedRoute> { Inset { BlockedScreen(onBack = { nav.popBackStack() }) } }
                    composable<DownloadsManagerRoute> { Inset { DownloadsManagerScreen(onBack = { nav.popBackStack() }) } }
                    composable<PlaylistRoute> { e -> PlaylistScreen(e.toRoute<PlaylistRoute>().id, onBack = { nav.popBackStack() }) }
                    composable<CollectionRoute> { e ->
                        val r = e.toRoute<CollectionRoute>()
                        CollectionScreen(r.url, r.title, onBack = { nav.popBackStack() })
                    }
                    composable<ArtistRoute> { e -> ArtistScreen(e.toRoute<ArtistRoute>().nameOrUrl, onBack = { nav.popBackStack() }) }
                }
            }

            AnimatedVisibility(
                visible = showPlayer && playerState.current != null,
                enter = slideInVertically(tween(280)) { it },
                exit = slideOutVertically(tween(240)) { it },
            ) {
                NowPlayingScreen(onClose = { showPlayer = false })
            }
        }

        menu?.let { (track, ctx) -> TrackMenuSheet(track, ctx, onDismiss = { menu = null }) }
        addTo?.let { AddToPlaylistSheet(it, onDismiss = { addTo = null }) }
        update?.let { u ->
            UpdateDialog(u, onDismiss = {
                update = null
                dismissedInSession = u.version
            })
        }
    }
}

/** Keeps a screen below the status bar (the ones with a header gradient handle it themselves). */
@Composable
private fun Inset(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding()) { content() }
}

/** Compact bottom navigation: small icons, bold label when active. */
@Composable
private fun BottomBar(tabs: List<Tab>, selected: (Tab) -> Boolean, onSelect: (Tab) -> Unit) {
    val c = LocalSpotifreeColors.current
    if (c.isGlassy) {
        val topBorder = Color.White.copy(alpha = 0.18f)
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f))
                    )
                )
                .drawBehind {
                    drawLine(topBorder, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                }
                .navigationBarsPadding()
                .height(60.dp),
        ) {
            tabs.forEach { tab ->
                val active = selected(tab)
                val tint = if (active) Color.White else Color.White.copy(alpha = 0.65f)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.18f))
                                .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(tab.selectedIcon, tab.label, tint = tint, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(2.dp))
                                Text(tab.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                        }
                    } else {
                        Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(tab.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    } else {
        val line = Color.White.copy(alpha = 0.06f)
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (c.amoled) Color.Black else Color(0xFA121212))
                .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .navigationBarsPadding()
                .height(60.dp),
        ) {
            tabs.forEach { tab ->
                val active = selected(tab)
                val tint = if (active) Color.White else c.textSecondary
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (active) tab.selectedIcon else tab.icon, tab.label, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(tab.label, color = tint, fontSize = 11.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

private suspend fun handleIntent(intent: Intent, actions: AppActions, onOpenPlayer: () -> Unit) {
    val graph = actions.graph
    if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "spotifree") {
        val data = intent.data ?: return
        val id = data.pathSegments.firstOrNull()
        when (data.host) {
            "playlist" -> when {
                id != null && graph.db.playlists().get(id) != null -> actions.openPlaylist(id)
                id == com.spotifree.app.data.model.Playlist.LIKED_ID -> actions.openPlaylist(id)
                else -> Unit
            }
            else -> Unit // spotifree:// just opens the app
        }
        return
    }
    when (intent.action) {
        MainActivity.ACTION_OPEN_PLAYER -> onOpenPlayer()
        Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
            if (graph.settings.current.offlineMode) return
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: return
            val url = Regex("""https?://\S+""").find(text)?.value ?: return
            val list = YouTubeIds.playlistId(url)
            val vid = YouTubeIds.videoId(url)
            when {
                vid != null -> {
                    val t = graph.music.trackInfo(vid) ?: Track(id = Track.idForVideo(vid), title = "YouTube", artist = "", videoId = vid)
                    actions.play(listOf(t))
                    onOpenPlayer()
                }
                list != null -> actions.openCollection(YouTubeIds.playlistUrl(list))
                url.contains("/channel/") || url.contains("/@") -> actions.openArtist(url)
            }
        }
        "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> {
            val q = intent.getStringExtra(SearchManager.QUERY).orEmpty()
            if (q.isBlank()) {
                graph.player.play()
                return
            }
            val found = if (graph.settings.current.offlineMode) {
                graph.db.tracks().searchLocal(q, 1).firstOrNull()?.toTrack()
            } else {
                runCatchingCancellable { graph.music.searchSongs(q, 1) }.getOrNull()?.firstOrNull()
            } ?: return
            actions.play(listOf(found))
        }
    }
}
