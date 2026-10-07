package io.github.deadeyebarb.tonearm.ui.discover

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.LibraryPick
import io.github.deadeyebarb.tonearm.integrations.MissingArtist
import io.github.deadeyebarb.tonearm.integrations.Recommender
import io.github.deadeyebarb.tonearm.integrations.RotationEntry
import io.github.deadeyebarb.tonearm.ui.LidarrSettingsRoute
import io.github.deadeyebarb.tonearm.ui.RequestRoute
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.YouTubePlayButton
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.request.RemoteCover
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudLoader
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.HudTopBar
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import java.text.NumberFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Composable
fun DiscoverScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    val session by c.sessions.active.collectAsStateWithLifecycle()
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    val lidarrReady = integrations.lidarr != null
    // Until the Tonearm server has been looked for, "not there" would only be a guess.
    var looked by remember(session?.id) { mutableStateOf(false) }
    LaunchedEffect(session?.id) {
        runCatching { c.tonearmServer.refresh(session) }
        looked = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            HudTopBar(
                title = "Discover",
                subtitle = when {
                    server?.history == true -> "RECOMMENDATIONS FROM WHAT YOU PLAY"
                    !looked -> "LOOKING FOR THE TONEARM SERVER…"
                    else -> "NEEDS THE TONEARM SERVER FOR RECOMMENDATIONS"
                },
            )
        },
    ) { padding ->
        if (server?.history != true) {
            if (looked) NotConnected(actions, lidarrReady, Modifier.padding(padding))
            else Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { HudLoader() }
            return@Scaffold
        }
        val vm = rememberLoader("discover", session?.id, server?.baseUrl) { c.recommender.discover() }
        LoadContent(vm, Modifier.padding(padding)) { data ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "${NumberFormat.getIntegerInstance().format(data.scrobblesLast30Days)} PLAYS IN THE LAST 30 DAYS",
                            style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent,
                        )
                        Spacer(Modifier.height(10.dp))
                        HudButton(
                            "Request", { actions.navigate(if (lidarrReady) RequestRoute() else LidarrSettingsRoute) }, Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.CloudDownload,
                        )
                    }
                }
                if (data.libraryEmpty) item { EmptyLibraryBanner(lidarrReady, actions) }
                item { DailyCard(actions) }
                item { DiscoveryPicksPanel(actions) }
                item { AiPicksPanel(actions) }
                if (data.rotation.isNotEmpty()) {
                    item { HudSectionHeader("Heavy rotation") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                            items(data.rotation, key = { it.name }) { entry -> RotationCircle(entry, actions) }
                        }
                    }
                }
                if (data.rediscover.isNotEmpty()) {
                    item { HudSectionHeader("Rediscover") }
                    items(data.rediscover, key = { "re:" + it.artist.id }) { pick -> PickRow(pick, actions) }
                }
                if (data.similarInLibrary.isNotEmpty()) {
                    item { HudSectionHeader("In your library, worth a spin") }
                    items(data.similarInLibrary, key = { "sim:" + it.artist.id }) { pick -> PickRow(pick, actions) }
                }
                if (data.notInLibrary.isNotEmpty()) {
                    item { HudSectionHeader("Not in your library yet") }
                    items(data.notInLibrary, key = { "miss:" + it.name }) { missing -> MissingRow(missing, lidarrReady, actions) }
                }
                if (!data.hasSimilarData && !data.libraryEmpty) {
                    item {
                        Text(
                            "Your music server returned no similar-artist data, so only history-based picks are shown. " +
                                "In Navidrome, enable the Last.fm agent (ND_LASTFM_APIKEY) to get “similar” and “not in your library” suggestions.",
                            style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (data.rotation.isEmpty() && data.rediscover.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Rounded.AutoAwesome, "Not enough history yet",
                            "Play some music (here, on the desktop, or from YouTube Music in either) and recommendations will appear here.",
                            Modifier.height(320.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryBanner(lidarrReady: Boolean, actions: AppActions) {
    val hud = Hud.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, hud.accent2.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        Text("LIBRARY EMPTY", style = MaterialTheme.typography.labelMedium, color = hud.accent2)
        Spacer(Modifier.height(6.dp))
        Text(
            if (lidarrReady) {
                "Your music server has no music yet. Request what you listen to and Lidarr will fetch it; recommendations follow once it's scanned."
            } else {
                "Your music server has no music yet. Connect Lidarr to request what you listen to."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!lidarrReady) {
            Spacer(Modifier.height(12.dp))
            HudButton("Connect Lidarr", { actions.navigate(LidarrSettingsRoute) }, Modifier.fillMaxWidth(), filled = false)
        }
    }
}

@Composable
private fun NotConnected(actions: AppActions, lidarrReady: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DiscoveryPicksPanel(actions, Modifier.padding(bottom = 8.dp))
        AiPicksPanel(actions, Modifier.padding(bottom = 8.dp))
        EmptyState(
            Icons.Rounded.AutoAwesome, "Recommendations from what you play",
            "The Tonearm server on your music server (1.5 or later) keeps what you play in this app and on the desktop, " +
                "YouTube Music included, and Tonearm turns it into picks: what's in heavy rotation, favourites you haven't " +
                "played in a while, similar artists in your library, and artists you don't have yet, ready to request.",
            Modifier.heightIn(min = 280.dp),
        )
        HudButton(
            if (lidarrReady) "Request music" else "Connect Lidarr",
            { actions.navigate(if (lidarrReady) RequestRoute() else LidarrSettingsRoute) },
            Modifier.fillMaxWidth(), filled = false,
        )
    }
}

@Composable
private fun RotationCircle(entry: RotationEntry, actions: AppActions) {
    val hud = Hud.colors
    val youtube = LocalContext.current.container.settings.state.collectAsStateWithLifecycle().value.youtubeFallback
    Column(
        Modifier.width(120.dp).clip(MaterialTheme.shapes.medium)
            .clickable {
                when {
                    entry.artist != null -> actions.openArtist(entry.artist.id)
                    youtube -> actions.playFromYouTube(entry.name)
                    else -> actions.requestArtist(entry.name)
                }
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(
            entry.artist?.coverArt, Modifier.size(100.dp).glowBorder(hud.accent.copy(alpha = 0.6f), CircleShape, glow = 6.dp),
            size = CoverSize.CARD, shape = CircleShape, placeholder = Icons.Rounded.Person,
        )
        Spacer(Modifier.height(6.dp))
        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        Text("${entry.scrobbles} PLAYS", style = MaterialTheme.typography.labelSmall, color = hud.accent)
        if (entry.artist == null) HudTag(if (youtube) "▶ YOUTUBE MUSIC" else "NOT IN LIBRARY", color = hud.accent2)
    }
}

@Composable
private fun PickRow(pick: LibraryPick, actions: AppActions) {
    val hud = Hud.colors
    ListItem(
        modifier = Modifier.clickable { actions.openArtist(pick.artist.id) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            CoverArt(
                pick.artist.coverArt, Modifier.size(48.dp).border(1.dp, hud.accent.copy(alpha = 0.4f), CircleShape),
                size = CoverSize.THUMB, shape = CircleShape, placeholder = Icons.Rounded.Person,
            )
        },
        headlineContent = { Text(pick.artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(pick.reason.uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = { playArtist(actions, pick.artist.id) }) {
                Box(Modifier.size(36.dp).border(1.5.dp, hud.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, "Play ${pick.artist.name}", tint = hud.accent)
                }
            }
        },
    )
}

/** Shuffles everything by a library artist. */
internal fun playArtist(actions: AppActions, artistId: String) = actions.launch {
    val api = actions.container.api
    val songs = coroutineScope {
        api.artist(artistId).album.map { album -> async { api.album(album.id).song } }.awaitAll().flatten()
    }
    actions.play(songs, shuffle = true)
}

@Composable
private fun MissingRow(missing: MissingArtist, lidarrReady: Boolean, actions: AppActions) {
    val hud = Hud.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteCover(missing.imageUrl, Icons.Rounded.Person, Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(missing.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(missing.reason.uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        YouTubePlayButton(missing.name)
        if (Recommender.normalize(missing.name) in actions.requestedArtists) {
            HudTag("REQUESTED", color = hud.ok, filled = true)
        } else {
            HudButton("Request", { actions.requestArtist(missing.name) }, filled = false)
        }
    }
}
