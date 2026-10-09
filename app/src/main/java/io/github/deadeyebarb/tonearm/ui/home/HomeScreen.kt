package io.github.deadeyebarb.tonearm.ui.home

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.connect.Mix
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.ForYouItem
import io.github.deadeyebarb.tonearm.integrations.ForYouKind
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.ui.AlbumListRoute
import io.github.deadeyebarb.tonearm.ui.ConnectRoute
import io.github.deadeyebarb.tonearm.ui.DownloadsRoute
import io.github.deadeyebarb.tonearm.ui.HistoryRoute
import io.github.deadeyebarb.tonearm.ui.MixRoute
import io.github.deadeyebarb.tonearm.ui.SettingsRoute
import io.github.deadeyebarb.tonearm.ui.common.AlbumCard
import io.github.deadeyebarb.tonearm.ui.common.AlbumRow
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.Load
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.plural
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.discover.MixCollage
import io.github.deadeyebarb.tonearm.ui.mix.MixPromptField
import io.github.deadeyebarb.tonearm.ui.mix.dailyMixDay
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudLoader
import io.github.deadeyebarb.tonearm.ui.theme.HudPanel
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.Orbitron
import io.github.deadeyebarb.tonearm.ui.theme.PulseDot
import io.github.deadeyebarb.tonearm.ui.theme.glow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class HomeData(
    val newest: List<Album>,
    val recent: List<Album>,
    val frequent: List<Album>,
    val random: List<Album>,
    val starred: List<Album>,
    val forYou: List<ForYouItem>,
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
            val forYou = async { runCatching { c.forYou.build(c.api.active()) }.getOrDefault(emptyList()) }
            HomeData(newest.await(), recent.await(), frequent.await(), random.await(), starred.await(), forYou.await())
        }
    }
    val online by c.network.online.collectAsStateWithLifecycle()
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    // What the Tonearm server can do (History, daily mixes) shows once it has been looked for.
    LaunchedEffect(session?.id) { runCatching { c.tonearmServer.refresh(session) } }
    // Outside Home's loader: the first call of the day makes them, which can take a while. Checked on every return to
    // Home, so a row left open overnight makes way for the new day's.
    val daily = viewModel { DailyMixesModel(c) }
    LifecycleResumeEffect(session?.id, server?.dailyMixes) {
        session?.let(daily::load)
        onPauseOrDispose {}
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            HomeHeader(
                serverName = session?.config?.name,
                mtls = session?.config?.clientCert != null,
                // Down too while a refresh that failed leaves the last content up.
                online = online && vm.state !is Load.Failed && vm.stale == null,
                history = server?.stats == true,
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
                item(key = "mix_prompt") {
                    MixPromptField(onGo = { actions.navigate(MixRoute(prompt = it)) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                if (data.forYou.isNotEmpty()) item(key = "for_you") { ForYouRow(data.forYou, actions) }
                if (daily.building || daily.mixes.isNotEmpty()) item(key = "daily_mixes") { DailyMixesRow(daily, actions) }
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
private fun HomeHeader(serverName: String?, mtls: Boolean, online: Boolean, history: Boolean, onSettings: () -> Unit) {
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
        if (history) IconButton(onClick = { actions.navigate(HistoryRoute) }) { Icon(Icons.Rounded.History, "History") }
        IconButton(onClick = { actions.navigate(ConnectRoute) }) { Icon(Icons.Rounded.Devices, "Devices") }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

private suspend fun playStarred(actions: AppActions) {
    val songs = actions.container.api.starred().song
    if (songs.isEmpty()) actions.message("No liked songs yet") else actions.play(songs, shuffle = true, context = MediaIds.STARRED)
}

/** This week's picks, albums liked lately and ones not played in months, each with why it's there. */
@Composable
private fun ForYouRow(items: List<ForYouItem>, actions: AppActions) {
    val hud = Hud.colors
    Column {
        SectionHeader("For you")
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(items, key = { it.kind.name + it.album.id }) { item ->
                val (tag, color) = when (item.kind) {
                    ForYouKind.PICK -> "Pick" to hud.accent
                    ForYouKind.LIKED -> "Liked" to hud.accent2
                    ForYouKind.REDISCOVER -> "Rediscover" to hud.ok
                }
                AlbumCard(
                    item.album, Modifier.width(156.dp),
                    tag = {
                        HudTag(
                            tag, Modifier.background(hud.void.copy(alpha = 0.75f), MaterialTheme.shapes.extraSmall),
                            color = color, filled = item.kind == ForYouKind.PICK,
                        )
                    },
                    caption = item.caption to color,
                ) { actions.openAlbum(item.album.id) }
            }
        }
    }
}

/**
 * Today's daily mixes for Home's row; kept while Home is on the back stack, asked for again on a new day (the server's,
 * which starts at 4:00) or another server.
 */
private class DailyMixesModel(private val c: AppContainer) : ViewModel() {
    var mixes by mutableStateOf(emptyList<Mix>())
        private set
    /** The server is making today's. */
    var building by mutableStateOf(false)
        private set
    var serverId by mutableStateOf<String?>(null)
        private set
    /** The day [mixes] are for. */
    private var day: String? = null
    /** The server and day asked for; null to ask again. */
    private var loaded: Pair<String, String>? = null
    private var job: Job? = null

    fun load(session: ServerSession) {
        val key = session.id to dailyMixDay()
        if (key == loaded) return
        loaded = key
        // An answer still on its way from the server before, or for the day before, isn't wanted any more.
        job?.cancel()
        job = viewModelScope.launch {
            val daily = try {
                c.mixer.daily(session) { building = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            building = false
            if (daily == null || daily.running) {
                // No answer, no server that makes them (yet), or still at it after two minutes: asked again when Home
                // shows next. A bad moment keeps what's shown; another music server's or another day's mixes go.
                loaded = null
                if (serverId != session.id || daily != null && day != daily.day) mixes = emptyList()
                return@launch
            }
            // Nothing to make them from yet: no row, and Home stays calm about it.
            daily.problem?.let { Log.i("Tonearm", "Daily mixes: $it") }
            serverId = session.id
            day = daily.day
            // Kept as the day they're for: should the server's clock not be at 4:00 yet, Home asks again next time.
            loaded = session.id to daily.day.ifEmpty { key.second }
            mixes = daily.mixes.filter { it.songs.isNotEmpty() }
        }
    }
}

@Composable
private fun DailyMixesRow(daily: DailyMixesModel, actions: AppActions) {
    Column {
        SectionHeader("Daily mixes")
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            if (daily.building) {
                item(key = "building") { DailyMixPlaceholder() }
            }
            val serverId = daily.serverId ?: return@LazyRow
            items(daily.mixes, key = { it.id }) { mix -> DailyMixCard(mix, serverId, actions) }
        }
    }
}

/** A daily mix: its covers with a play button, title and the artists in it. Tap opens it. */
@Composable
private fun DailyMixCard(mix: Mix, serverId: String, actions: AppActions) {
    val hud = Hud.colors
    Column(Modifier.width(156.dp).clip(MaterialTheme.shapes.medium).clickable { actions.navigate(MixRoute(dailyId = mix.id)) }.padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            MixCollage(mix.songs.mapNotNull { it.coverArt }.distinct().take(4), serverId, Modifier.fillMaxSize())
            IconButton(onClick = { actions.playEntries(mix.songs.map { QueueSong(serverId, it) }) }, Modifier.align(Alignment.BottomEnd)) {
                Box(
                    Modifier.size(40.dp).border(1.5.dp, hud.accent, CircleShape).background(hud.void.copy(alpha = 0.7f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Play ${mix.title}", tint = hud.accent) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(mix.title.uppercase(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = hud.text)
        Text(
            (mix.subtitle ?: plural(mix.songs.size, "track")).uppercase(),
            style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DailyMixPlaceholder() {
    HudPanel(Modifier.width(156.dp).padding(6.dp).aspectRatio(1f)) {
        HudLoader(Modifier.align(Alignment.Center), label = "BUILDING…")
    }
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
