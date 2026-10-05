package io.github.deadeyebarb.tonearm.ui.library

import io.github.deadeyebarb.tonearm.ui.common.ErrorState
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.subsonic.Starred
import androidx.compose.runtime.LaunchedEffect
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudRule
import io.github.deadeyebarb.tonearm.ui.theme.HudTopBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.ui.SettingsRoute
import io.github.deadeyebarb.tonearm.ui.common.AlbumGrid
import io.github.deadeyebarb.tonearm.ui.common.AlbumPagingViewModel
import io.github.deadeyebarb.tonearm.ui.common.AlbumRow
import io.github.deadeyebarb.tonearm.ui.common.ArtistCircle
import io.github.deadeyebarb.tonearm.ui.common.ArtistRow
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.PlayShuffleButtons
import io.github.deadeyebarb.tonearm.ui.common.PlaylistRow
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.TextInputDialog
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import kotlinx.coroutines.launch

private enum class LibraryTab(val label: String) { ARTISTS("Artists"), ALBUMS("Albums"), PLAYLISTS("Playlists"), GENRES("Genres"), FAVORITES("Liked"), PHONE("This phone") }

@Composable
fun LibraryScreen() {
    val actions = LocalActions.current
    val tabs = LibraryTab.entries
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                HudTopBar(
                    title = "Library",
                    subtitle = "ARCHIVE INDEX",
                    actions = { IconButton(onClick = { actions.navigate(SettingsRoute) }) { Icon(Icons.Rounded.Settings, "Settings") } },
                )
                PrimaryScrollableTabRow(
                    selectedTabIndex = pager.currentPage,
                    edgePadding = 16.dp,
                    containerColor = Color.Transparent,
                    contentColor = Hud.colors.accent,
                    divider = { HudRule() },
                ) {
                    tabs.forEachIndexed { i, tab ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            selectedContentColor = Hud.colors.accent,
                            unselectedContentColor = Hud.colors.dim,
                            text = { Text(tab.label.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.padding(padding).fillMaxSize()) { page ->
            when (tabs[page]) {
                LibraryTab.ARTISTS -> ArtistsPage()
                LibraryTab.ALBUMS -> AlbumsPage()
                LibraryTab.PLAYLISTS -> PlaylistsPage()
                LibraryTab.GENRES -> GenresPage()
                LibraryTab.FAVORITES -> FavoritesPage()
                LibraryTab.PHONE -> PhonePage()
            }
        }
    }
}

@Composable
private fun serverKey(): String? {
    val session by LocalContext.current.container.sessions.active.collectAsStateWithLifecycle()
    return session?.id
}

@Composable
private fun ArtistsPage() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = rememberLoader("artists", serverKey()) { c.api.artists() }
    LoadContent(vm) { index ->
        LazyColumn(Modifier.fillMaxSize()) {
            for (group in index) {
                stickyHeader(key = "h:${group.name}") {
                    Text(
                        "// ${group.name}", style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent,
                        modifier = Modifier.fillMaxWidth().background(Hud.colors.void.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                items(group.artist, key = { "a:${group.name}:${it.id}" }) { artist -> ArtistRow(artist) { actions.openArtist(artist.id) } }
            }
        }
    }
}

private val SORTS = listOf(
    AlbumListType.NEWEST, AlbumListType.BY_NAME, AlbumListType.BY_ARTIST, AlbumListType.FREQUENT,
    AlbumListType.RECENT, AlbumListType.STARRED, AlbumListType.RANDOM,
)

@Composable
private fun AlbumsPage() {
    val c = LocalContext.current.container
    var sort by rememberSaveable { mutableStateOf(AlbumListType.NEWEST) }
    val vm = viewModel(key = "albums|${serverKey()}|$sort") {
        AlbumPagingViewModel { offset -> c.api.albumList(sort, AlbumPagingViewModel.PAGE_SIZE, offset) }
    }
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SORTS) { type ->
                FilterChip(
                    selected = sort == type, onClick = { sort = type },
                    label = { Text(type.title.uppercase(), style = MaterialTheme.typography.labelMedium) },
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
        AlbumGrid(vm)
    }
}

@Composable
private fun PlaylistsPage() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = rememberLoader("playlists", serverKey()) { c.api.playlists() }
    var creating by remember { mutableStateOf(false) }
    LoadContent(vm) { playlists ->
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ListItem(
                    modifier = Modifier.clickable { creating = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text("New playlist", color = MaterialTheme.colorScheme.primary) },
                )
            }
            items(playlists, key = { it.id }) { playlist -> PlaylistRow(playlist) { actions.openPlaylist(playlist.id) } }
        }
    }
    if (creating) {
        TextInputDialog(
            title = "New playlist", label = "Name", confirmLabel = "Create",
            onConfirm = { name ->
                creating = false
                actions.launch {
                    c.api.createPlaylist(name, emptyList())
                    vm.reload(silent = true)
                }
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun GenresPage() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = rememberLoader("genres", serverKey()) { c.api.genres().sortedBy { it.value.lowercase() } }
    LoadContent(vm) { genres ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(genres, key = { it.value }) { genre ->
                ListItem(
                    modifier = Modifier.clickable { actions.openGenre(genre.value) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(genre.value) },
                    supportingContent = { Text("${genre.albumCount} albums · ${genre.songCount} songs") },
                )
            }
        }
    }
}

@Composable
private fun FavoritesPage() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    // Without the server, likes on the phone still show.
    val vm = rememberLoader("starred", serverKey()) { runCatching { c.api.starred() } }
    val pending by c.likes.pending.items.collectAsStateWithLifecycle()
    val phoneLikes by c.likes.phone.collectAsStateWithLifecycle()
    val phoneSongs by c.local.songs.collectAsStateWithLifecycle()
    // Likes made on the desktop arrive here too.
    LaunchedEffect(Unit) { if (c.likes.syncNow()) c.likes.resolveNow() }
    LaunchedEffect(phoneLikes.isNotEmpty()) { if (phoneLikes.isNotEmpty() && phoneSongs == null) runCatching { c.local.load() } }
    val likedOnPhone = remember(phoneLikes, phoneSongs) { phoneSongs.orEmpty().filter { it.id in phoneLikes } }
    LoadContent(vm) { result ->
        val starred = result.getOrNull() ?: Starred()
        val failure = result.exceptionOrNull()
        if (failure != null && likedOnPhone.isEmpty()) {
            ErrorState(failure.userMessage(), onRetry = { vm.reload() })
            return@LoadContent
        }
        if (starred.song.isEmpty() && starred.album.isEmpty() && starred.artist.isEmpty() && pending.isEmpty() && likedOnPhone.isEmpty()) {
            EmptyState(Icons.Rounded.FavoriteBorder, "Nothing liked yet", "Tap the heart on songs, albums and artists to collect them here.")
            return@LoadContent
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (failure != null) {
                item {
                    Text(
                        "Your server can't be reached (${failure.userMessage()}), so only what's on this phone shows.",
                        style = MaterialTheme.typography.bodySmall, color = Hud.colors.danger, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (likedOnPhone.isNotEmpty()) {
                val entries = likedOnPhone.map { QueueSong(LocalMusic.SOURCE_ID, it) }
                item { SectionHeader("On this phone") }
                itemsIndexed(entries, key = { _, e -> "ph:${e.song.id}" }) { i, entry ->
                    SongRow(entry.song, serverId = LocalMusic.SOURCE_ID, onClick = { actions.playEntries(entries, i) })
                }
            }
            if (pending.isNotEmpty()) {
                val waiting = pending.mapNotNull { like ->
                    like.ref.youtubeId?.let { id ->
                        QueueSong(YouTubeMusic.SOURCE_ID, Song(id = id, title = like.ref.title, artist = like.ref.artist, duration = like.ref.duration, coverArt = like.ref.coverUrl))
                    }
                }
                item { SectionHeader("Waiting for download") }
                item {
                    Text(
                        "Liked on YouTube Music and requested in Lidarr. They play from YouTube Music until they're in your library.",
                        style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                itemsIndexed(waiting, key = { _, e -> "p:${e.song.id}" }) { i, entry ->
                    SongRow(entry.song, serverId = YouTubeMusic.SOURCE_ID, onClick = { actions.playEntries(waiting, i) })
                }
            }
            if (starred.album.isNotEmpty()) {
                item { SectionHeader("Albums") }
                item { AlbumRow(starred.album) { actions.openAlbum(it.id) } }
            }
            if (starred.artist.isNotEmpty()) {
                item { SectionHeader("Artists") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                        items(starred.artist, key = { it.id }) { artist -> ArtistCircle(artist) { actions.openArtist(artist.id) } }
                    }
                }
            }
            if (starred.song.isNotEmpty()) {
                item { SectionHeader("Songs") }
                item {
                    PlayShuffleButtons(
                        onPlay = { actions.play(starred.song, context = MediaIds.STARRED) },
                        onShuffle = { actions.play(starred.song, shuffle = true, context = MediaIds.STARRED) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(starred.song, key = { _, s -> "s:${s.id}" }) { i, song ->
                    SongRow(song, onClick = { actions.play(starred.song, i, context = MediaIds.STARRED) })
                }
            }
        }
    }
}
