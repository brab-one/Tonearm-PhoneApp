package io.github.deadeyebarb.tonearm.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.ui.LocalAlbumRoute
import io.github.deadeyebarb.tonearm.ui.common.AlbumCard
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.PlayShuffleButtons
import io.github.deadeyebarb.tonearm.ui.common.SongLeading
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.detail.MetaLine
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudLoader

/** Library → This phone: the music files stored on the phone, by album. */
@Composable
fun PhonePage() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    var granted by remember { mutableStateOf(c.local.hasPermission()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                Icons.Rounded.PhoneAndroid, "Music on this phone",
                "Tonearm can play the music files stored on this phone too, next to your server's library. Android asks first whether it may see them.",
                Modifier.weight(1f),
            )
            HudButton("Allow access to music", { ask.launch(LocalMusic.PERMISSION) }, Modifier.fillMaxWidth())
        }
        return
    }
    val songs by c.local.songs.collectAsStateWithLifecycle()
    LaunchedEffect(granted, songs == null) { if (songs == null) runCatching { c.local.load() } }
    val list = songs ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HudLoader() }
    if (list.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, "No music on this phone", "Music files on the phone (in Music, Download and other folders) show up here.")
        return
    }
    val albums = remember(list) { c.local.albums(list) }
    val all = remember(list) { list.map { QueueSong(LocalMusic.SOURCE_ID, it) } }
    LazyVerticalGrid(GridCells.Adaptive(152.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(8.dp)) {
                Text(
                    "${list.size} songs in ${albums.size} albums, played straight from the phone's storage.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim,
                )
                Spacer(Modifier.height(8.dp))
                PlayShuffleButtons(onPlay = { actions.playEntries(all) }, onShuffle = { actions.playEntries(all, shuffle = true) })
            }
        }
        items(albums, key = { it.id }) { album ->
            AlbumCard(album, serverId = LocalMusic.SOURCE_ID) { actions.navigate(LocalAlbumRoute(album.id)) }
        }
    }
}

/** An album stored on the phone. */
@Composable
fun PhoneAlbumScreen(albumId: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val songs by c.local.songs.collectAsStateWithLifecycle()
    LaunchedEffect(songs == null) { if (songs == null) runCatching { c.local.load() } }
    val album = remember(songs, albumId) { songs?.let { c.local.albums(it).firstOrNull { a -> a.id == albumId } } }
    DetailScaffold(album?.name.orEmpty()) {
        if (album == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (songs == null) HudLoader() else Text("This album isn't on the phone anymore") }
            return@DetailScaffold
        }
        val entries = remember(album) { album.song.map { QueueSong(LocalMusic.SOURCE_ID, it) } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CoverArt(
                        album.coverArt, Modifier.size(220.dp).border(1.dp, Hud.colors.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium),
                        serverId = LocalMusic.SOURCE_ID, size = CoverSize.LARGE, shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(album.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = Hud.colors.text)
                    Text(album.artistLabel, style = MaterialTheme.typography.titleMedium, color = Hud.colors.accent2)
                    MetaLine(listOfNotNull("On this phone", album.year?.toString(), "${album.songCount} tracks").joinToString(" // "))
                    Spacer(Modifier.height(12.dp))
                    PlayShuffleButtons(onPlay = { actions.playEntries(entries) }, onShuffle = { actions.playEntries(entries, shuffle = true) })
                }
            }
            itemsIndexed(album.song, key = { _, s -> s.id }) { i, song ->
                SongRow(song, onClick = { actions.playEntries(entries, i) }, serverId = LocalMusic.SOURCE_ID, leading = SongLeading.TrackNumber, showAlbum = false)
            }
        }
    }
}
