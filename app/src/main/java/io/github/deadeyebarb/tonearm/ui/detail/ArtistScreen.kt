package io.github.deadeyebarb.tonearm.ui.detail

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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.connect.SimilarArtist
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.ArtistInfo
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.ui.common.AlbumCard
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
import io.github.deadeyebarb.tonearm.ui.request.RemoteCover
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
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
        actions = {
            page?.let { p ->
                IconButton(onClick = { actions.moreLike(p.artist.name, p.artist.name, "the artist ${p.artist.name}", artistId = p.artist.id, cover = p.artist.coverArt) }) {
                    Icon(Icons.Rounded.AutoAwesome, "More like this")
                }
                StarButton(serverId, StarKind.ARTIST, p.artist.id, p.artist.starred != null)
            }
        },
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
                item(span = full) { SimilarArtists(data) }
            }
        }
    }
}

/**
 * Similar artists: from the Tonearm server when it has them (Deezer's related artists, including ones you
 * don't have, which open on YouTube Music), else the music server's own similar artists in the library.
 */
@Composable
private fun SimilarArtists(page: ArtistPage) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    val own = page.info?.similarArtist.orEmpty().filter { it.inLibrary }.distinctBy { it.id }
    var found by remember(page.artist.name) { mutableStateOf<List<SimilarArtist>?>(null) }
    LaunchedEffect(page.artist.name, server?.discovery) {
        if (server?.discovery == true) found = runCatching { c.connect.similarArtists(page.artist.name) }.getOrNull()
    }
    val similar = found
    if (similar.isNullOrEmpty()) {
        if (own.isEmpty()) return
        Column {
            SectionHeader("Similar artists")
            LazyRow { items(own, key = { it.id }) { artist -> ArtistCircle(artist) { actions.openArtist(artist.id) } } }
        }
        return
    }
    // Ones in the library open there; the rest on YouTube Music.
    fun open(artist: SimilarArtist) = actions.launch {
        val key = Names.normalize(artist.artist)
        val id = if (!artist.inLibrary) null else own.firstOrNull { Names.normalize(it.name) == key }?.id
            ?: c.api.search(artist.artist, artistCount = 10, albumCount = 0, songCount = 0).artist.firstOrNull { Names.normalize(it.name) == key }?.id
        if (id != null) actions.openArtist(id) else actions.findOnYouTube(artist.artist)
    }
    Column {
        SectionHeader("Similar artists")
        LazyRow { items(similar, key = { it.artist }) { artist -> SimilarCircle(artist) { open(artist) } } }
    }
}

@Composable
private fun SimilarCircle(artist: SimilarArtist, onClick: () -> Unit) {
    val hud = Hud.colors
    Column(
        Modifier.width(120.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RemoteCover(artist.imageUrl, Icons.Rounded.Person, Modifier.size(104.dp), shape = CircleShape)
        Spacer(Modifier.height(8.dp))
        Text(artist.artist, style = MaterialTheme.typography.titleSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        Text(
            if (artist.inLibrary) "IN LIBRARY" else "YOUTUBE MUSIC",
            style = MaterialTheme.typography.labelSmall, color = if (artist.inLibrary) hud.ok else hud.accent2,
        )
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
