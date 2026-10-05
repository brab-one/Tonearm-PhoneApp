package io.github.deadeyebarb.tonearm.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.ui.AlbumListRoute
import io.github.deadeyebarb.tonearm.ui.ConnectRoute
import io.github.deadeyebarb.tonearm.ui.DownloadsRoute
import io.github.deadeyebarb.tonearm.ui.SettingsRoute
import io.github.deadeyebarb.tonearm.ui.common.AlbumRow
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.Load
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.Orbitron
import io.github.deadeyebarb.tonearm.ui.theme.PulseDot
import io.github.deadeyebarb.tonearm.ui.theme.glow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class HomeData(
    val newest: List<Album>,
    val recent: List<Album>,
    val frequent: List<Album>,
    val random: List<Album>,
    val starred: List<Album>,
)

@Composable
fun HomeScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val session by c.sessions.active.collectAsStateWithLifecycle()
    val vm = rememberLoader("home", session?.id) {
        coroutineScope {
            val newest = async { c.api.albumList(AlbumListType.NEWEST, 20) }
            suspend fun optional(type: AlbumListType) = runCatching { c.api.albumList(type, 20) }.getOrDefault(emptyList())
            val recent = async { optional(AlbumListType.RECENT) }
            val frequent = async { optional(AlbumListType.FREQUENT) }
            val random = async { optional(AlbumListType.RANDOM) }
            val starred = async { optional(AlbumListType.STARRED) }
            HomeData(newest.await(), recent.await(), frequent.await(), random.await(), starred.await())
        }
    }
    val online by c.network.online.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            HomeHeader(
                serverName = session?.config?.name,
                mtls = session?.config?.clientCert != null,
                online = online && vm.state !is Load.Failed,
                onSettings = { actions.navigate(SettingsRoute) },
            )
        },
    ) { padding ->
        LoadContent(
            vm, Modifier.padding(padding),
            errorExtra = { TextButton(onClick = { actions.navigate(DownloadsRoute) }) { Text("Play downloaded music") } },
        ) { data ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HudButton("Shuffle all", { actions.shuffleAll() }, Modifier.weight(1f), icon = Icons.Rounded.Shuffle)
                        HudButton("Liked", { actions.launch { playStarred(actions) } }, Modifier.weight(1f), icon = Icons.Rounded.Favorite, filled = false)
                    }
                }
                albumSection(actions, AlbumListType.NEWEST, data.newest)
                albumSection(actions, AlbumListType.RECENT, data.recent)
                albumSection(actions, AlbumListType.FREQUENT, data.frequent)
                albumSection(actions, AlbumListType.STARRED, data.starred)
                albumSection(actions, AlbumListType.RANDOM, data.random)
            }
        }
    }
}

@Composable
private fun HomeHeader(serverName: String?, mtls: Boolean, online: Boolean, onSettings: () -> Unit) {
    val actions = LocalActions.current
    val hud = Hud.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "TONEARM",
                style = TextStyle(
                    fontFamily = Orbitron, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 5.sp,
                    brush = Brush.horizontalGradient(listOf(hud.accent, hud.accent2)),
                ).glow(hud.accent.copy(alpha = 0.7f), 24f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(if (online) hud.ok else hud.danger)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(if (online) "LINK ONLINE" else "LINK DOWN", serverName?.uppercase(), "mTLS".takeIf { mtls }).joinToString(" // "),
                    style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { actions.navigate(ConnectRoute) }) { Icon(Icons.Rounded.Devices, "Devices") }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

private suspend fun playStarred(actions: AppActions) {
    val songs = actions.container.api.starred().song
    if (songs.isEmpty()) actions.message("No liked songs yet") else actions.play(songs, shuffle = true, context = MediaIds.STARRED)
}

private fun LazyListScope.albumSection(actions: AppActions, type: AlbumListType, albums: List<Album>) {
    if (albums.isEmpty()) return
    item(key = type.name) {
        Column {
            SectionHeader(type.title) { actions.navigate(AlbumListRoute(type.name)) }
            AlbumRow(albums) { actions.openAlbum(it.id) }
        }
    }
}
