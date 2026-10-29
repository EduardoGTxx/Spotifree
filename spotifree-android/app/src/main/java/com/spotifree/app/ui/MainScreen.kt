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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import com.spotifree.app.ui.workout.WorkoutScreen
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

    // Update check once per launch (silent when offline or up to date)
    LaunchedEffect(Unit) {
        val r = runCatchingCancellable { graph.updates.check() }.getOrNull()
        if (r is UpdateCheck.Available && r.update.version != settings.dismissedUpdateVersion) update = r.update
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
        if (settings.enableTGym) add(Tab(WorkoutRoute, t("navWorkout"), Icons.Outlined.FitnessCenter, Icons.Filled.FitnessCenter))
    }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination

    CompositionLocalProvider(LocalActions provides actions) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            Scaffold(
                containerColor = c.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        if (playerState.current != null) MiniPlayer(onOpen = { showPlayer = true })
                        NavigationBar(containerColor = if (c.amoled) Color.Black else Color(0xF0121212)) {
                            tabs.forEach { tab ->
                                val selected = destination?.hierarchy?.any { d -> d.hasRoute(tab.route::class) } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = Color.White,
                                        indicatorColor = Color.Transparent,
                                        unselectedIconColor = c.textSecondary,
                                        unselectedTextColor = c.textSecondary,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
                    composable<HomeRoute> { HomeScreen() }
                    composable<SearchRoute> { SearchScreen() }
                    composable<LibraryRoute> { LibraryScreen() }
                    composable<WorkoutRoute> { WorkoutScreen() }
                    composable<SettingsRoute> { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable<StatsRoute> { StatsScreen(onBack = { nav.popBackStack() }) }
                    composable<RewindRoute> { RewindScreen(onBack = { nav.popBackStack() }) }
                    composable<SpotifyImportRoute> { SpotifyImportScreen(onBack = { nav.popBackStack() }) }
                    composable<YouTubeSignInRoute> { YouTubeSignInScreen(onBack = { nav.popBackStack() }) }
                    composable<BlockedRoute> { BlockedScreen(onBack = { nav.popBackStack() }) }
                    composable<DownloadsManagerRoute> { DownloadsManagerScreen(onBack = { nav.popBackStack() }) }
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
                scope.launch { graph.settings.update { it.copy(dismissedUpdateVersion = u.version) } }
            })
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
                // TGym asks for its gym playlist ("pl-gym-beast-mode"): the workout screen has the gym mix
                else -> actions.nav.navigate(WorkoutRoute) { launchSingleTop = true }
            }
            "workout" -> actions.nav.navigate(WorkoutRoute) { launchSingleTop = true }
            else -> Unit // spotifree:// just opens the app
        }
        return
    }
    when (intent.action) {
        MainActivity.ACTION_OPEN_PLAYER -> onOpenPlayer()
        Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
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
            val found = runCatchingCancellable { graph.music.searchSongs(q, 1) }.getOrNull()?.firstOrNull() ?: return
            actions.play(listOf(found))
        }
    }
}
