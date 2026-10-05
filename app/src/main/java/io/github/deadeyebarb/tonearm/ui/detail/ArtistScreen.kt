package io.github.deadeyebarb.tonearm.ui.detail

import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import android.text.Html
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.ArtistInfo
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.ui.common.AlbumCard
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.ArtistCircle
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.PlayShuffleButtons
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.StarButton
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private data class ArtistPage(val artist: Artist, val info: ArtistInfo?, val topSongs: List<Song>)

@Composable
fun ArtistScreen(id: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val serverId = actions.activeServerId
    val vm = rememberLoader("artist", serverId, id) {
        coroutineScope {
            val artist = c.api.artist(id)
            val info = async { c.api.artistInfo(id) }
            val top = async { c.api.topSongs(artist.name, 10) }
            ArtistPage(artist, info.await(), top.await())
        }
    }
    val page = vm.value
    DetailScaffold(
        title = page?.artist?.name.orEmpty(),
        actions = { page?.let { StarButton(serverId, StarKind.ARTIST, it.artist.id, it.artist.starred != null) } },
    ) {
        LoadContent(vm) { data ->
            val albums = data.artist.album.sortedByDescending { it.year ?: 0 }
            val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
            LazyVerticalGrid(GridCells.Adaptive(152.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
                item(span = full) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CoverArt(
                                data.artist.coverArt,
                                Modifier.size(112.dp).glowBorder(Hud.colors.accent, CircleShape, width = 1.5.dp, glow = 12.dp),
                                size = CoverSize.CARD, shape = CircleShape, placeholder = Icons.Rounded.Person,
                            )
                            Spacer(Modifier.width(18.dp))
                            Column {
                                Text(data.artist.name.uppercase(), style = MaterialTheme.typography.headlineSmall.glow(Hud.colors.accent.copy(alpha = 0.5f)))
                                MetaLine(if (data.artist.albumCount == 1) "1 album" else "${data.artist.albumCount} albums")
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        PlayShuffleButtons(
                            onPlay = { playArtist(actions, data.artist, shuffle = false) },
                            onShuffle = { playArtist(actions, data.artist, shuffle = true) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (data.topSongs.isNotEmpty()) {
                    item(span = full) { SectionHeader("Popular") }
                    itemsIndexed(data.topSongs.take(5), span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, song ->
                        SongRow(song, onClick = { actions.play(data.topSongs, i) })
                    }
                }
                item(span = full) { SectionHeader("Albums") }
                items(albums, key = { it.id }) { album -> AlbumCard(album) { actions.openAlbum(album.id) } }
                item(span = full) { MoreOnYouTube(data.artist) }
                val bio = data.info?.biography?.let(::cleanBiography)
                if (!bio.isNullOrBlank()) {
                    item(span = full) { SectionHeader("About") }
                    item(span = full) { Biography(bio) }
                }
                val similar = data.info?.similarArtist.orEmpty().filter { it.inLibrary }.distinctBy { it.id }
                if (similar.isNotEmpty()) {
                    item(span = full) { SectionHeader("Similar artists") }
                    item(span = full) {
                        LazyRow { items(similar, key = { it.id }) { artist -> ArtistCircle(artist) { actions.openArtist(artist.id) } } }
                    }
                }
            }
        }
    }
}

/** The artist's albums on YouTube Music that aren't in the library, and their YouTube Music page. */
@Composable
private fun MoreOnYouTube(artist: Artist) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    if (!settings.youtubeCatalog) return
    var found by remember(artist.id) { mutableStateOf<Pair<YtArtist, List<YtAlbum>>?>(null) }
    LaunchedEffect(artist.id) {
        val yt = runCatching { c.catalog.findArtist(artist.name) }.getOrNull() ?: return@LaunchedEffect
        val have = artist.album.map { Names.normalize(it.name) }.toSet()
        val missing = runCatching { c.catalog.artistPage(yt).albums }.getOrDefault(emptyList()).filter { Names.normalize(it.title) !in have }
        found = yt to missing
    }
    val (yt, missing) = found ?: return
    Column {
        SectionHeader("More on YouTube Music", onSeeAll = { actions.openYouTubeArtist(yt) })
        if (missing.isNotEmpty()) YouTubeAlbumRow(missing) { actions.openYouTubeAlbum(it) }
    }
}

private fun playArtist(actions: AppActions, artist: Artist, shuffle: Boolean) = actions.launch {
    val songs = coroutineScope {
        artist.album.sortedBy { it.year ?: 0 }.map { album -> async { actions.container.api.album(album.id).song } }.awaitAll().flatten()
    }
    actions.play(songs, shuffle = shuffle)
}

private fun cleanBiography(html: String): String =
    Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
        .replace(Regex("""\s*Read more on Last\.fm\.?\s*$"""), "")
        .trim()

@Composable
private fun Biography(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text, style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.animateContentSize().clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
