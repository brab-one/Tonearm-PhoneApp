package io.github.deadeyebarb.tonearm.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.connect.AiSearch
import io.github.deadeyebarb.tonearm.connect.WebSearch
import io.github.deadeyebarb.tonearm.connect.WebSong
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.Recommender
import io.github.deadeyebarb.tonearm.integrations.SearchRank
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.SearchResult
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.ui.RequestRoute
import io.github.deadeyebarb.tonearm.ui.common.AlbumRow
import io.github.deadeyebarb.tonearm.ui.common.ArtistCircle
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.ErrorState
import io.github.deadeyebarb.tonearm.ui.common.Load
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.detail.YouTubeAlbumRow
import io.github.deadeyebarb.tonearm.ui.detail.YouTubeArtistRow
import io.github.deadeyebarb.tonearm.ui.request.RemoteCover
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun YouTubeSearching() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text("Searching YouTube Music…", style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim)
    }
}

/** Songs that aren't on the server, playable from YouTube Music. */
private fun LazyListScope.youtubeResults(state: Load<List<Song>>?, onPlay: (List<QueueSong>, Int) -> Unit) {
    when (state) {
        null -> Unit
        Load.Loading -> item { YouTubeSearching() }
        is Load.Failed -> item {
            Text(
                "YouTube Music: ${state.error.userMessage()}",
                style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(16.dp),
            )
        }
        is Load.Ready -> if (state.value.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("On YouTube Music")
                    Text(
                        "Not on your server. These play from YouTube Music; like one to request it in Lidarr.",
                        style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            val queue = state.value.map { QueueSong(YouTubeMusic.SOURCE_ID, it) }
            itemsIndexed(state.value, key = { _, s -> "yt:" + s.id }) { i, song ->
                SongRow(song, serverId = YouTubeMusic.SOURCE_ID, showAlbum = false, onClick = { onPlay(queue, i) })
            }
        }
    }
}

/** "Not finding it?" → search Lidarr for the same query. */
@Composable
private fun RequestPrompt(query: String) {
    val actions = LocalActions.current
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("NOT IN YOUR LIBRARY?", style = MaterialTheme.typography.labelSmall, color = Hud.colors.dim)
        HudButton("Request “$query” via Lidarr", { actions.navigate(RequestRoute(query)) }, Modifier.fillMaxWidth(), filled = false)
    }
}

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    /** Asks the Tonearm server's AI about the search; it answers in the background. */
    fun askAi() {
        val q = lastQuery.takeIf { it.isNotEmpty() } ?: return
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            while (true) {
                ai = try {
                    c.connect.aiSearch(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AiSearch(q, problem = e.userMessage())
                }
                if (ai?.running != true) break
                delay(4_000)
            }
        }
    }

    var state by mutableStateOf<Load<SearchResult>?>(null)
        private set
    /** Songs on YouTube Music that aren't in the library; null when that's turned off. */
    var youtube by mutableStateOf<Load<List<Song>>?>(null)
        private set
    var ytArtists by mutableStateOf<List<YtArtist>>(emptyList())
        private set
    var ytAlbums by mutableStateOf<List<YtAlbum>>(emptyList())
        private set
    /** Matching songs stored on the phone. */
    var phone by mutableStateOf<List<Song>>(emptyList())
        private set
    /** Deezer's matches, through the Tonearm server. */
    var web by mutableStateOf<WebSearch?>(null)
        private set
    /** What the server's AI makes of the search, once asked. */
    var ai by mutableStateOf<AiSearch?>(null)
        private set
    private var job: Job? = null
    private var aiJob: Job? = null
    private var lastQuery = ""

    fun search(query: String, debounce: Boolean = true) {
        val q = query.trim()
        if (q == lastQuery && state !is Load.Failed) return
        lastQuery = q
        job?.cancel()
        if (q.isEmpty()) {
            state = null
            youtube = null
            ytArtists = emptyList()
            ytAlbums = emptyList()
            phone = emptyList()
            web = null
            ai = null
            aiJob?.cancel()
            return
        }
        web = null
        ai = null
        aiJob?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(300)
            if (state == null) state = Load.Loading
            val yt = if (c.settings.state.value.youtubeFallback) async { runCatching { c.youtube.searchSongs(q, 15) } } else null
            if (c.local.hasPermission()) launch { phone = runCatching { c.local.load(); c.local.search(q).take(30) }.getOrDefault(emptyList()) }
            if (c.tonearmServer.server.value?.discovery == true) launch { web = runCatching { c.connect.webSearch(q) }.getOrNull() }
            if (c.settings.state.value.youtubeCatalog) {
                launch { ytArtists = runCatching { c.catalog.searchArtists(q, 8) }.getOrDefault(emptyList()) }
                launch { ytAlbums = runCatching { c.catalog.searchAlbums(q, 12) }.getOrDefault(emptyList()) }
            }
            youtube = if (yt != null) Load.Loading else null
            val library = try {
                Load.Ready(c.api.search(q, artistCount = 12, albumCount = 20, songCount = 60))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
            state = library
            youtube = yt?.await()?.fold(
                onSuccess = { songs ->
                    // Leave out what the library already has.
                    val have = (library as? Load.Ready)?.value?.song.orEmpty().map { Recommender.key(it.artist.orEmpty(), it.title) }.toSet()
                    Load.Ready(songs.filter { Recommender.key(it.artist.orEmpty(), it.title) !in have })
                },
                onFailure = { Load.Failed(it) },
            )
        }
    }
}

/** A best match from one of the sources. */
private sealed interface BestMatch {
    val key: String
    data class Own(val entry: QueueSong, override val key: String) : BestMatch
    data class YouTube(val entry: QueueSong, override val key: String) : BestMatch
    data class Web(val song: WebSong, override val key: String) : BestMatch
}

/**
 * The songs that fit the search best, whatever their source: the library, YouTube Music or Deezer. On a tie
 * the library's copy wins, so YouTube Music and Deezer only come first when they have a better match.
 */
private fun bestMatches(query: String, library: List<Song>, serverId: String?, youtube: List<Song>, web: List<WebSong>): List<BestMatch> {
    fun key(artist: String?, title: String) = Names.normalize(artist.orEmpty()) + "|" + Names.normalize(SongMatch.cleanTitle(title))
    val scored = buildList {
        if (serverId != null) library.forEach { add(Triple(BestMatch.Own(QueueSong(serverId, it), key(it.artist, it.title)) as BestMatch, SearchRank.score(query, it.title, it.artist, it.album), 0)) }
        youtube.forEach { add(Triple(BestMatch.YouTube(QueueSong(YouTubeMusic.SOURCE_ID, it), key(it.artist, it.title)), SearchRank.score(query, it.title, it.artist, it.album), 1)) }
        web.forEach { add(Triple(BestMatch.Web(it, key(it.artist, it.title)), SearchRank.score(query, it.title, it.artist, it.album), 2)) }
    }
    val seen = HashSet<String>()
    return scored.filter { it.second >= SearchRank.GOOD }
        .sortedWith(compareByDescending<Triple<BestMatch, Double, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
        .filter { seen.add(it.key) }
        .take(5)
}

@Composable
private fun BestMatchRow(match: BestMatch) {
    val actions = LocalActions.current
    when (match) {
        is BestMatch.Own -> SongRow(match.entry.song, serverId = match.entry.serverId, onClick = { actions.playEntries(listOf(match.entry), 0) })
        is BestMatch.YouTube -> SongRow(match.entry.song, serverId = match.entry.serverId, showAlbum = false, onClick = { actions.playRadio(match.entry) })
        is BestMatch.Web -> FoundRow(match.song.title, "${match.song.artist}${match.song.album?.let { " · $it" } ?: ""}", "DEEZER // PLAYS FROM YOUTUBE MUSIC", match.song.coverUrl) {
            actions.playFound(match.song.artist, match.song.title)
        }
    }
}

/** A song or album known only by name: tap to play it (or open it) from the library or YouTube Music. */
@Composable
private fun FoundRow(title: String, subtitle: String, note: String, cover: String?, onClick: () -> Unit) {
    val hud = Hud.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteCover(cover, Icons.Rounded.MusicNote, Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = MaterialTheme.typography.labelSmall, color = hud.accent2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The AI's take on the search: a button to ask, then what it found (songs play, albums open). */
private fun LazyListScope.aiResults(ai: AiSearch?, available: Boolean, onAsk: () -> Unit) {
    if (!available) return
    item {
        val actions = LocalActions.current
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            when {
                ai == null -> HudButton("Ask the AI about this search", onAsk, Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoAwesome, filled = false)
                ai.running -> Text("The AI is thinking… (it can take a minute)", style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim)
                ai.problem != null -> Text("AI: ${ai.problem}", style = MaterialTheme.typography.bodySmall, color = Hud.colors.danger)
                ai.hits.isEmpty() -> Text("The AI found nothing for this.", style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim)
                else -> Column {
                    SectionHeader("The AI suggests")
                    ai.hits.forEach { hit ->
                        FoundRow(hit.title ?: hit.album.orEmpty(), hit.artist + if (hit.title == null) " · album" else "", hit.why.uppercase(), null) {
                            if (hit.title != null) actions.playFound(hit.artist, hit.title) else actions.openFoundAlbum(hit.artist, hit.album.orEmpty())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    val vm = viewModel { SearchViewModel(c) }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    LaunchedEffect(query) { vm.search(query) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("SEARCH THE ARCHIVE_", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Hud.colors.accent) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Hud.colors.accent,
                    unfocusedBorderColor = Hud.colors.line,
                    focusedContainerColor = Hud.colors.panel.copy(alpha = 0.8f),
                    unfocusedContainerColor = Hud.colors.panel.copy(alpha = 0.6f),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    vm.search(query, debounce = false)
                }),
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val state = vm.state) {
                null -> EmptyState(Icons.Rounded.Search, "Search your library", "Find artists, albums and songs on your server.")
                Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is Load.Failed -> ErrorState(state.error.userMessage(), onRetry = { vm.search(query, debounce = false) })
                is Load.Ready -> {
                    val result = state.value
                    val youtube = vm.youtube
                    val youtubeHits = (youtube as? Load.Ready)?.value.orEmpty()
                    if (result.artist.isEmpty() && result.album.isEmpty() && result.song.isEmpty() && youtubeHits.isEmpty() && vm.phone.isEmpty() &&
                        vm.web?.songs.isNullOrEmpty() && vm.ai == null && server?.recommendations != true
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(Icons.Rounded.SearchOff, "Nothing found for “${query.trim()}”", modifier = Modifier.weight(1f))
                            if (youtube == Load.Loading) YouTubeSearching()
                            RequestPrompt(query.trim())
                        }
                    } else {
                        val best = remember(result, youtubeHits, vm.web) {
                            bestMatches(query.trim(), result.song, actions.activeServerId, youtubeHits, vm.web?.songs.orEmpty())
                        }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            if (best.isNotEmpty()) {
                                item { SectionHeader("Best matches") }
                                items(best, key = { "best:" + it.key }) { match -> BestMatchRow(match) }
                            }
                            aiResults(vm.ai, server?.recommendations == true, onAsk = vm::askAi)
                            if (result.artist.isNotEmpty()) {
                                item {
                                    Column {
                                        SectionHeader("Artists")
                                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                                            items(result.artist, key = { it.id }) { artist -> ArtistCircle(artist) { actions.openArtist(artist.id) } }
                                        }
                                    }
                                }
                            }
                            if (result.album.isNotEmpty()) {
                                item {
                                    Column {
                                        SectionHeader("Albums")
                                        AlbumRow(result.album) { actions.openAlbum(it.id) }
                                    }
                                }
                            }
                            if (result.song.isNotEmpty()) {
                                item { SectionHeader("Songs") }
                                itemsIndexed(result.song, key = { _, s -> s.id }) { i, song ->
                                    SongRow(song, onClick = { actions.play(result.song, i) })
                                }
                            }
                            if (vm.phone.isNotEmpty()) {
                                val entries = vm.phone.map { QueueSong(LocalMusic.SOURCE_ID, it) }
                                item { SectionHeader("On this phone") }
                                itemsIndexed(entries, key = { _, e -> "ph:" + e.song.id }) { i, entry ->
                                    SongRow(entry.song, serverId = LocalMusic.SOURCE_ID, onClick = { actions.playEntries(entries, i) })
                                }
                            }
                            if (vm.ytArtists.isNotEmpty()) {
                                item {
                                    Column {
                                        SectionHeader("Artists on YouTube Music")
                                        YouTubeArtistRow(vm.ytArtists) { actions.openYouTubeArtist(it) }
                                    }
                                }
                            }
                            if (vm.ytAlbums.isNotEmpty()) {
                                item {
                                    Column {
                                        SectionHeader("Albums on YouTube Music")
                                        YouTubeAlbumRow(vm.ytAlbums) { actions.openYouTubeAlbum(it) }
                                    }
                                }
                            }
                            youtubeResults(youtube) { queue, index -> actions.playEntries(queue, index) }
                            item { RequestPrompt(query.trim()) }
                        }
                    }
                }
            }
        }
    }
}
