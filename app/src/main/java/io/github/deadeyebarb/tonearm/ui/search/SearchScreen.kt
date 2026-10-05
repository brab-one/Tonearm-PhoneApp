package io.github.deadeyebarb.tonearm.ui.search

import io.github.deadeyebarb.tonearm.ui.detail.YouTubeAlbumRow
import io.github.deadeyebarb.tonearm.ui.detail.YouTubeArtistRow
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
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
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.Recommender
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
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
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
    var state by mutableStateOf<Load<SearchResult>?>(null)
        private set
    /** Songs on YouTube Music that aren't in the library; null when that's turned off. */
    var youtube by mutableStateOf<Load<List<Song>>?>(null)
        private set
    var ytArtists by mutableStateOf<List<YtArtist>>(emptyList())
        private set
    var ytAlbums by mutableStateOf<List<YtAlbum>>(emptyList())
        private set
    private var job: Job? = null
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
            return
        }
        job = viewModelScope.launch {
            if (debounce) delay(300)
            if (state == null) state = Load.Loading
            val yt = if (c.settings.state.value.youtubeFallback) async { runCatching { c.youtube.searchSongs(q, 15) } } else null
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

@Composable
fun SearchScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
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
                    if (result.artist.isEmpty() && result.album.isEmpty() && result.song.isEmpty() && youtubeHits.isEmpty()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(Icons.Rounded.SearchOff, "Nothing found for “${query.trim()}”", modifier = Modifier.weight(1f))
                            if (youtube == Load.Loading) YouTubeSearching()
                            RequestPrompt(query.trim())
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
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
