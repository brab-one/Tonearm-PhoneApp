package io.github.deadeyebarb.tonearm.ui

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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
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
import io.github.deadeyebarb.tonearm.ui.common.LocalPendingLikes
import io.github.deadeyebarb.tonearm.ui.common.LocalPhoneLikes
import io.github.deadeyebarb.tonearm.ui.common.LocalPlaying
import io.github.deadeyebarb.tonearm.ui.common.LocalStarOverrides
import io.github.deadeyebarb.tonearm.ui.connect.ConnectScreen
import io.github.deadeyebarb.tonearm.ui.detail.AlbumListScreen
import io.github.deadeyebarb.tonearm.ui.detail.AlbumScreen
import io.github.deadeyebarb.tonearm.ui.detail.ArtistScreen
import io.github.deadeyebarb.tonearm.ui.detail.GenreScreen
import io.github.deadeyebarb.tonearm.ui.detail.PlaylistScreen
import io.github.deadeyebarb.tonearm.ui.detail.YouTubeAlbumScreen
import io.github.deadeyebarb.tonearm.ui.detail.YouTubeArtistScreen
import io.github.deadeyebarb.tonearm.ui.discover.DailyScreen
import io.github.deadeyebarb.tonearm.ui.discover.DiscoverScreen
import io.github.deadeyebarb.tonearm.ui.discover.MoreLikeScreen
import io.github.deadeyebarb.tonearm.ui.downloads.DownloadsScreen
import io.github.deadeyebarb.tonearm.ui.history.HistoryScreen
import io.github.deadeyebarb.tonearm.ui.home.HomeScreen
import io.github.deadeyebarb.tonearm.ui.library.LibraryScreen
import io.github.deadeyebarb.tonearm.ui.mix.MixScreen
import io.github.deadeyebarb.tonearm.ui.library.PhoneAlbumScreen
import io.github.deadeyebarb.tonearm.ui.player.MiniPlayer
import io.github.deadeyebarb.tonearm.ui.player.NowPlayingScreen
import io.github.deadeyebarb.tonearm.ui.request.LidarrRequestDialog
import io.github.deadeyebarb.tonearm.ui.request.RequestScreen
import io.github.deadeyebarb.tonearm.ui.search.SearchScreen
import io.github.deadeyebarb.tonearm.ui.settings.EqualizerScreen
import io.github.deadeyebarb.tonearm.ui.settings.LidarrSettingsScreen
import io.github.deadeyebarb.tonearm.ui.settings.ServerEditScreen
import io.github.deadeyebarb.tonearm.ui.settings.ServersScreen
import io.github.deadeyebarb.tonearm.ui.settings.SettingsScreen
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudBackground
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

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
    // Lidarr's queue and wanted list, for the status icons on songs from outside the library. Only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                container.integrations.requireLidarrOrNull()?.let { (config, key) -> runCatching { container.fetches.refresh(config, key) } }
                delay(60_000)
            }
        }
    }
    LaunchedEffect(openPlayerRequests) {
        if (openPlayerRequests > 0 && list.servers.isNotEmpty()) actions.openNowPlaying()
    }
    // Removing the last server brings back the welcome setup.
    val noServers = list.servers.isEmpty()
    LaunchedEffect(noServers) {
        if (!noServers) return@LaunchedEffect
        val top = nav.currentBackStackEntry
        val onWelcome = top?.destination?.hasRoute<ServerEditRoute>() == true && top.toRoute<ServerEditRoute>().welcome
        if (!onWelcome) nav.navigate(ServerEditRoute(welcome = true)) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBars = list.servers.isNotEmpty() && destination?.hasRoute<NowPlayingRoute>() != true
    // A page belongs to the tab it was opened from: the nearest tab below it on the back stack. Every page is a
    // composable, so the back stack of the navigator that shows them has them all. NavHost hands that navigator its
    // back stack when it sets the graph, which is done once there's a current entry.
    val pages = remember(nav) { nav.navigatorProvider.getNavigator(ComposeNavigator::class.java) }
    val pagesShown = entry != null
    val backStack by remember(pages, pagesShown) { if (pagesShown) pages.backStack else MutableStateFlow(emptyList<NavBackStackEntry>()) }
        .collectAsStateWithLifecycle()
    val currentTab = backStack.asReversed().firstNotNullOfOrNull { e -> Tab.entries.firstOrNull { e.destination.hasRoute(it.route::class) } }
    val selectTab: (Tab) -> Unit = { tab ->
        if (tab == currentTab) {
            // The tab you're in: back to its first page.
            nav.popBackStack(tab.route, inclusive = false)
        } else {
            nav.navigate(tab.route) {
                // Home, not the graph's start: after the first setup that's the welcome page, which is gone.
                popUpTo<HomeRoute> { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // Sideways the tabs move to a rail on the left, so the bottom keeps only the mini player and more of each page shows.
    val configuration = LocalConfiguration.current
    val rail = showBars && configuration.screenWidthDp > configuration.screenHeightDp

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
                    Column(if (rail) Modifier.navigationBarsPadding() else Modifier) {
                        if (player.current != null) MiniPlayer(player, onOpen = actions::openNowPlaying)
                        if (!rail) HudNavBar(selected = currentTab, onSelect = selectTab)
                    }
                }
            },
        ) { padding ->
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            val railInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)
            Row(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
                if (rail) HudNavRail(selected = currentTab, onSelect = selectTab, modifier = Modifier.windowInsetsPadding(railInsets))
                NavHost(
                    nav, startDestination,
                    modifier = Modifier.weight(1f).then(if (rail) Modifier.consumeWindowInsets(railInsets) else Modifier),
                    enterTransition = { fadeIn(tween(200)) },
                    exitTransition = { fadeOut(tween(200)) },
                ) {
                    composable<HomeRoute> { HomeScreen() }
                    composable<DiscoverRoute> { DiscoverScreen() }
                    composable<DailyRoute> { DailyScreen() }
                    composable<HistoryRoute> { HistoryScreen() }
                    composable<MixRoute> {
                        val r = it.toRoute<MixRoute>()
                        MixScreen(r.prompt, r.dailyId)
                    }
                    composable<ConnectRoute> { ConnectScreen() }
                    composable<RequestRoute> { RequestScreen(it.toRoute<RequestRoute>().query) }
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
                    composable<MoreLikeRoute> { MoreLikeScreen(it.toRoute<MoreLikeRoute>()) }
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
        }
        actions.playlistPicker?.let { items -> PlaylistPickerDialog(items) { actions.playlistPicker = null } }
        actions.lidarrRequest?.let { name -> LidarrRequestDialog(name) { actions.lidarrRequest = null } }
        actions.confirm?.let { confirm ->
            AlertDialog(
                onDismissRequest = { actions.confirm = null },
                title = { Text(confirm.title) },
                text = { Text(confirm.text) },
                confirmButton = { TextButton(onClick = { actions.confirm = null; confirm.onConfirm() }) { Text(confirm.action, color = Hud.colors.danger) } },
                dismissButton = { TextButton(onClick = { actions.confirm = null }) { Text("Cancel") } },
            )
        }
    }
}

/** The tabs down the left side, for a phone held sideways: icons only, the active one lit up like on the bottom bar. */
@Composable
private fun HudNavRail(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val hud = Hud.colors
    Column(
        modifier
            .fillMaxHeight()
            .width(72.dp)
            .background(Brush.horizontalGradient(listOf(hud.void.copy(alpha = 0.98f), hud.panel.copy(alpha = 0.92f))))
            .drawBehind {
                drawLine(
                    Brush.verticalGradient(listOf(Color.Transparent, hud.accent.copy(alpha = 0.6f), Color.Transparent)),
                    Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx(),
                )
            }
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        for (tab in Tab.entries) {
            val active = tab == selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                    .drawBehind {
                        if (active) {
                            val h = size.height * 0.5f
                            val y = (size.height - h) / 2
                            drawLine(hud.accent.copy(alpha = 0.35f), Offset(size.width, y), Offset(size.width, y + h), 7.dp.toPx())
                            drawLine(hud.accent, Offset(size.width, y), Offset(size.width, y + h), 2.dp.toPx())
                            drawCircle(
                                Brush.radialGradient(listOf(hud.accent.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width, size.height / 2), radius = size.height),
                                radius = size.height, center = Offset(size.width, size.height / 2),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(tab.icon, tab.label, tint = if (active) hud.accent else hud.dim)
            }
        }
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
