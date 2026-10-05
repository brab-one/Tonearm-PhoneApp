package io.github.deadeyebarb.tonearm.ui

import io.github.deadeyebarb.tonearm.ui.library.PhoneAlbumScreen
import io.github.deadeyebarb.tonearm.ui.common.LocalPhoneLikes
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import io.github.deadeyebarb.tonearm.ui.detail.YouTubeAlbumScreen
import io.github.deadeyebarb.tonearm.ui.detail.YouTubeArtistScreen
import io.github.deadeyebarb.tonearm.ui.common.LocalPendingLikes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.media.serverId
import io.github.deadeyebarb.tonearm.media.songId
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.LocalDownloads
import io.github.deadeyebarb.tonearm.ui.common.LocalNowPlaying
import io.github.deadeyebarb.tonearm.ui.common.LocalPlaying
import io.github.deadeyebarb.tonearm.ui.common.LocalStarOverrides
import io.github.deadeyebarb.tonearm.ui.connect.ConnectScreen
import io.github.deadeyebarb.tonearm.ui.detail.AlbumListScreen
import io.github.deadeyebarb.tonearm.ui.detail.AlbumScreen
import io.github.deadeyebarb.tonearm.ui.detail.ArtistScreen
import io.github.deadeyebarb.tonearm.ui.detail.GenreScreen
import io.github.deadeyebarb.tonearm.ui.detail.PlaylistScreen
import io.github.deadeyebarb.tonearm.ui.discover.BrainarrScreen
import io.github.deadeyebarb.tonearm.ui.discover.DailyScreen
import io.github.deadeyebarb.tonearm.ui.discover.DiscoverScreen
import io.github.deadeyebarb.tonearm.ui.downloads.DownloadsScreen
import io.github.deadeyebarb.tonearm.ui.home.HomeScreen
import io.github.deadeyebarb.tonearm.ui.library.LibraryScreen
import io.github.deadeyebarb.tonearm.ui.player.MiniPlayer
import io.github.deadeyebarb.tonearm.ui.player.NowPlayingScreen
import io.github.deadeyebarb.tonearm.ui.request.LidarrRequestDialog
import io.github.deadeyebarb.tonearm.ui.request.RequestScreen
import io.github.deadeyebarb.tonearm.ui.search.SearchScreen
import io.github.deadeyebarb.tonearm.ui.settings.EqualizerScreen
import io.github.deadeyebarb.tonearm.ui.settings.LidarrSettingsScreen
import io.github.deadeyebarb.tonearm.ui.settings.MalojaSettingsScreen
import io.github.deadeyebarb.tonearm.ui.settings.ServerEditScreen
import io.github.deadeyebarb.tonearm.ui.settings.ServersScreen
import io.github.deadeyebarb.tonearm.ui.settings.SettingsScreen
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudBackground
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder

private enum class Tab(val route: Any, val label: String, val icon: ImageVector) {
    HOME(HomeRoute, "Home", Icons.Rounded.Home),
    DISCOVER(DiscoverRoute, "Discover", Icons.Rounded.AutoAwesome),
    LIBRARY(LibraryRoute, "Library", Icons.Rounded.LibraryMusic),
    SEARCH(SearchRoute, "Search", Icons.Rounded.Search),
    DOWNLOADS(DownloadsRoute, "Downloads", Icons.Rounded.DownloadForOffline),
}

@Composable
fun TonearmRoot(container: AppContainer, openPlayerRequests: Int, requestNotificationPermission: () -> Unit) {
    val serverList by container.servers.state.collectAsStateWithLifecycle()
    val list = serverList ?: return
    val nav = rememberNavController()
    val actions = remember(nav) { AppActions(container, nav, requestNotificationPermission) }
    val startDestination: Any = remember { if (list.servers.isEmpty()) ServerEditRoute(welcome = true) else HomeRoute }

    val player by container.player.state.collectAsStateWithLifecycle()
    val downloads by container.downloads.entries.collectAsStateWithLifecycle()
    val stars by container.starred.overrides.collectAsStateWithLifecycle()
    val pendingLikes by container.likes.pending.items.collectAsStateWithLifecycle()
    val phoneLikes by container.likes.phone.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        container.messages.flow.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }
    LaunchedEffect(openPlayerRequests) {
        if (openPlayerRequests > 0 && list.servers.isNotEmpty()) actions.openNowPlaying()
    }

    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBars = list.servers.isNotEmpty() && destination?.hasRoute<NowPlayingRoute>() != true

    CompositionLocalProvider(
        LocalActions provides actions,
        LocalDownloads provides downloads,
        LocalStarOverrides provides stars,
        LocalPendingLikes provides remember(pendingLikes) { pendingLikes.mapNotNull { it.ref.youtubeId }.toSet() },
        LocalPhoneLikes provides phoneLikes,
        LocalNowPlaying provides player.current?.let { it.serverId to it.songId },
        LocalPlaying provides player.isPlaying,
    ) {
        Box(Modifier.fillMaxSize()) {
        HudBackground()
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) { HudSnackbar(it) } },
            bottomBar = {
                if (showBars) {
                    Column {
                        if (player.current != null) MiniPlayer(player, onOpen = actions::openNowPlaying)
                        HudNavBar(
                            selected = Tab.entries.firstOrNull { tab -> destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true },
                            onSelect = { tab ->
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            NavHost(
                nav, startDestination,
                modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(200)) },
            ) {
                composable<HomeRoute> { HomeScreen() }
                composable<DiscoverRoute> { DiscoverScreen() }
                composable<DailyRoute> { DailyScreen() }
                composable<BrainarrRoute> { BrainarrScreen() }
                composable<ConnectRoute> { ConnectScreen() }
                composable<RequestRoute> { RequestScreen(it.toRoute<RequestRoute>().query) }
                composable<MalojaSettingsRoute> { MalojaSettingsScreen() }
                composable<LidarrSettingsRoute> { LidarrSettingsScreen() }
                composable<LibraryRoute> { LibraryScreen() }
                composable<SearchRoute> { SearchScreen() }
                composable<DownloadsRoute> { DownloadsScreen() }
                composable<AlbumRoute> { AlbumScreen(it.toRoute<AlbumRoute>().id) }
                composable<ArtistRoute> { ArtistScreen(it.toRoute<ArtistRoute>().id) }
                composable<PlaylistRoute> { PlaylistScreen(it.toRoute<PlaylistRoute>().id) }
                composable<GenreRoute> { GenreScreen(it.toRoute<GenreRoute>().name) }
                composable<AlbumListRoute> { AlbumListScreen(it.toRoute<AlbumListRoute>().type) }
                composable<LocalAlbumRoute> { PhoneAlbumScreen(it.toRoute<LocalAlbumRoute>().id) }
                composable<YtArtistRoute> {
                    val r = it.toRoute<YtArtistRoute>()
                    YouTubeArtistScreen(YtArtist(r.url, r.name, r.imageUrl, r.subscribers))
                }
                composable<YtAlbumRoute> {
                    val r = it.toRoute<YtAlbumRoute>()
                    YouTubeAlbumScreen(YtAlbum(r.url, r.title, r.artist, r.imageUrl))
                }
                composable<SettingsRoute> { SettingsScreen() }
                composable<ServersRoute> { ServersScreen() }
                composable<EqualizerRoute> { EqualizerScreen() }
                composable<ServerEditRoute> { backStack ->
                    val route = backStack.toRoute<ServerEditRoute>()
                    ServerEditScreen(route.serverId, route.welcome, onSaved = {
                        if (route.welcome) {
                            nav.navigate(HomeRoute) { popUpTo(nav.graph.id) { inclusive = true } }
                        } else {
                            nav.popBackStack()
                        }
                    })
                }
                composable<NowPlayingRoute>(
                    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(300)) },
                    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, tween(300)) },
                ) { NowPlayingScreen(onBack = { nav.popBackStack() }) }
            }
        }
        }
        actions.playlistPicker?.let { items -> PlaylistPickerDialog(items) { actions.playlistPicker = null } }
        actions.lidarrRequest?.let { name -> LidarrRequestDialog(name) { actions.lidarrRequest = null } }
    }
}

/** Glass bottom bar: a glowing rail on top, the active tab lit up in the accent color. */
@Composable
private fun HudNavBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    val hud = Hud.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(hud.panel.copy(alpha = 0.92f), hud.void.copy(alpha = 0.98f))))
            .drawBehind {
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, hud.accent.copy(alpha = 0.6f), Color.Transparent)),
                    Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
                )
            }
            .navigationBarsPadding()
            .height(64.dp),
    ) {
        for (tab in Tab.entries) {
            val active = tab == selected
            val color = if (active) hud.accent else hud.dim
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                    .drawBehind {
                        if (active) {
                            val w = size.width * 0.4f
                            val x = (size.width - w) / 2
                            drawLine(hud.accent.copy(alpha = 0.35f), Offset(x, 0f), Offset(x + w, 0f), 7.dp.toPx())
                            drawLine(hud.accent, Offset(x, 0f), Offset(x + w, 0f), 2.dp.toPx())
                            drawCircle(
                                Brush.radialGradient(listOf(hud.accent.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 0.6f),
                                radius = size.width * 0.6f, center = Offset(size.width / 2, 0f),
                            )
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, null, tint = color)
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp).let { if (active) it.glow(hud.accent, 10f) else it },
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun HudSnackbar(data: SnackbarData) {
    val hud = Hud.colors
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glowBorder(hud.accent.copy(alpha = 0.7f), MaterialTheme.shapes.small, glow = 6.dp)
            .background(hud.panelHigh.copy(alpha = 0.97f), MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("▸ " + data.visuals.message, style = MaterialTheme.typography.bodyMedium, color = hud.text)
    }
}
