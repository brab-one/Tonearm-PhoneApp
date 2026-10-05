package io.github.deadeyebarb.tonearm.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.PlayShuffleButtons
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist

/** An artist's YouTube Music page: who they are, popular songs and albums, all playable and requestable. */
@Composable
fun YouTubeArtistScreen(artist: YtArtist) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = rememberLoader("yt-artist", artist.url) {
        val page = c.catalog.artistPage(artist)
        val inLibrary = runCatching { c.api.search(artist.name, artistCount = 5, albumCount = 0, songCount = 0).artist }.getOrDefault(emptyList())
            .firstOrNull { Names.normalize(it.name) == Names.normalize(artist.name) }
        page to inLibrary
    }
    DetailScaffold(artist.name) {
        LoadContent(vm) { (page, inLibrary) ->
            val hud = Hud.colors
            val top = page.topSongs.map { QueueSong(YouTubeMusic.SOURCE_ID, it) }
            var expanded by remember { mutableStateOf(false) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CoverArt(
                            page.artist.imageUrl, Modifier.size(160.dp).glowBorder(hud.accent2.copy(alpha = 0.6f), CircleShape, glow = 8.dp),
                            serverId = YouTubeMusic.SOURCE_ID, size = CoverSize.CARD, shape = CircleShape, placeholder = Icons.Rounded.Person,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(page.artist.name, style = MaterialTheme.typography.headlineSmall, color = hud.text, textAlign = TextAlign.Center)
                        MetaLine(listOfNotNull("YouTube Music", page.artist.subscribers?.let { "${compact(it)} subscribers" }).joinToString(" // "))
                        page.description?.let { bio ->
                            Text(
                                bio, style = MaterialTheme.typography.bodyMedium, color = hud.dim,
                                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 12.dp).clickable { expanded = !expanded },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        if (top.isNotEmpty()) {
                            PlayShuffleButtons(onPlay = { actions.playEntries(top) }, onShuffle = { actions.playEntries(top, shuffle = true) })
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (top.isNotEmpty()) {
                                HudButton("Radio", { actions.playRadio(top.first()) }, Modifier.weight(1f), icon = Icons.Rounded.Radio, filled = false)
                            }
                            if (inLibrary != null) {
                                HudButton("In your library", { actions.openArtist(inLibrary.id) }, Modifier.weight(1f), icon = Icons.Rounded.LibraryMusic, filled = false)
                            } else {
                                HudButton("Request", { actions.requestArtistByName(page.artist.name) }, Modifier.weight(1f), icon = Icons.Rounded.CloudDownload, filled = false)
                            }
                        }
                    }
                }
                if (page.albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    item { YouTubeAlbumRow(page.albums) { actions.openYouTubeAlbum(it) } }
                }
                if (top.isNotEmpty()) {
                    item { SectionHeader("Popular") }
                    itemsIndexed(top, key = { _, e -> "t:" + e.song.id }) { i, entry ->
                        SongRow(entry.song, serverId = YouTubeMusic.SOURCE_ID, showAlbum = false, onClick = { actions.playEntries(top, i) })
                    }
                }
            }
        }
    }
}

/** An album on YouTube Music: its tracks (playable), and a request for the real thing in Lidarr. */
@Composable
fun YouTubeAlbumScreen(album: YtAlbum) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = rememberLoader("yt-album", album.url) {
        val page = c.catalog.albumPage(album)
        val inLibrary = runCatching { c.api.search(album.title, artistCount = 0, albumCount = 10, songCount = 0).album }.getOrDefault(emptyList())
            .firstOrNull {
                Names.normalize(it.name) == Names.normalize(album.title) &&
                    (page.album.artist == null || Names.normalize(it.artistLabel) == Names.normalize(page.album.artist))
            }
        page to inLibrary
    }
    DetailScaffold(album.title) {
        LoadContent(vm) { (page, inLibrary) ->
            val hud = Hud.colors
            val songs = page.songs.map { QueueSong(YouTubeMusic.SOURCE_ID, it) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CoverArt(
                            page.album.imageUrl, Modifier.size(220.dp).glowBorder(hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium, glow = 8.dp),
                            serverId = YouTubeMusic.SOURCE_ID, size = CoverSize.LARGE, shape = MaterialTheme.shapes.medium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(page.album.title, style = MaterialTheme.typography.headlineSmall, color = hud.text, textAlign = TextAlign.Center)
                        page.album.artist?.let { artist ->
                            Text(
                                artist, style = MaterialTheme.typography.titleMedium, color = hud.accent2,
                                modifier = Modifier.clickable { actions.findOnYouTube(artist) },
                            )
                        }
                        MetaLine(listOf("YouTube Music", "${songs.size} tracks").joinToString(" // "))
                        Spacer(Modifier.height(12.dp))
                        if (songs.isNotEmpty()) PlayShuffleButtons(onPlay = { actions.playEntries(songs) }, onShuffle = { actions.playEntries(songs, shuffle = true) })
                        Spacer(Modifier.height(8.dp))
                        if (inLibrary != null) {
                            HudButton("In your library", { actions.openAlbum(inLibrary.id) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.LibraryMusic, filled = false)
                        } else {
                            HudButton(
                                "Request album in Lidarr", { actions.requestAlbum(page.album.title, page.album.artist.orEmpty()) },
                                Modifier.fillMaxWidth(), icon = Icons.Rounded.CloudDownload, filled = false,
                            )
                        }
                    }
                }
                itemsIndexed(songs, key = { i, e -> "$i:" + e.song.id }) { i, entry ->
                    SongRow(entry.song, serverId = YouTubeMusic.SOURCE_ID, showAlbum = false, onClick = { actions.playEntries(songs, i) })
                }
            }
        }
    }
}

@Composable
fun YouTubeAlbumRow(albums: List<YtAlbum>, onClick: (YtAlbum) -> Unit) {
    val hud = Hud.colors
    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
        items(albums, key = { it.url }) { album ->
            Column(Modifier.width(156.dp).clickable { onClick(album) }.padding(6.dp)) {
                CoverArt(album.imageUrl, Modifier.size(144.dp), serverId = YouTubeMusic.SOURCE_ID, size = CoverSize.CARD)
                Text(album.title, style = MaterialTheme.typography.titleSmall, color = hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                album.artist?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
fun YouTubeArtistRow(artists: List<YtArtist>, onClick: (YtArtist) -> Unit) {
    val hud = Hud.colors
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
        items(artists, key = { it.url }) { artist ->
            Column(Modifier.width(120.dp).clickable { onClick(artist) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverArt(
                    artist.imageUrl, Modifier.size(104.dp).glowBorder(hud.accent2.copy(alpha = 0.5f), CircleShape, glow = 6.dp),
                    serverId = YouTubeMusic.SOURCE_ID, size = CoverSize.CARD, shape = CircleShape, placeholder = Icons.Rounded.Person,
                )
                Text(
                    artist.name, style = MaterialTheme.typography.labelLarge, color = hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.0fK".format(n / 1_000.0)
    else -> n.toString()
}
