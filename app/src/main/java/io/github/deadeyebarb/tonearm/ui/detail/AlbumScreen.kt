package io.github.deadeyebarb.tonearm.ui.detail

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.ui.common.ActionMenu
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.DownloadAllButton
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.MenuAction
import io.github.deadeyebarb.tonearm.ui.common.PlayShuffleButtons
import io.github.deadeyebarb.tonearm.ui.common.SongLeading
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.StarButton
import io.github.deadeyebarb.tonearm.ui.common.formatTotal
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.cornerBrackets
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder

@Composable
fun AlbumScreen(id: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val serverId = actions.activeServerId
    val vm = rememberLoader("album", serverId, id) { c.api.album(id) }
    val album = vm.value
    DetailScaffold(
        title = album?.name.orEmpty(),
        actions = {
            if (album != null) {
                StarButton(serverId, StarKind.ALBUM, album.id, album.starred != null)
                DownloadAllButton(actions.entries(album.song))
                AlbumMenu(album)
            }
        },
    ) {
        LoadContent(vm) { album ->
            val context = serverId?.let { MediaIds.album(it, album.id) }
            val discs = album.song.groupBy { it.discNumber ?: 1 }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { AlbumHeader(album, onPlay = { shuffle -> actions.play(album.song, shuffle = shuffle, context = context) }) }
                for ((disc, songs) in discs) {
                    if (discs.size > 1) {
                        item(key = "disc:$disc") {
                            val title = album.discTitles.firstOrNull { it.disc == disc }?.title
                            Text(
                                "// DISC $disc" + if (!title.isNullOrBlank()) " · ${title.uppercase()}" else "",
                                style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(songs, key = { "s:${it.id}" }) { song ->
                        SongRow(
                            song, leading = SongLeading.TrackNumber, showAlbum = false,
                            onClick = { actions.play(album.song, album.song.indexOf(song), context = context) },
                        )
                    }
                }
                item {
                    Text(
                        "END OF ALBUM // ${album.song.size} TRACKS // ${formatTotal(album.duration).uppercase()}",
                        style = MaterialTheme.typography.labelSmall, color = Hud.colors.dim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(album: Album, onPlay: (shuffle: Boolean) -> Unit) {
    val actions = LocalActions.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        CoverArt(
            album.coverArt,
            Modifier.fillMaxWidth(0.7f).widthIn(max = 360.dp).aspectRatio(1f)
                .cornerBrackets(Hud.colors.accent)
                .glowBorder(Hud.colors.accent.copy(alpha = 0.55f), MaterialTheme.shapes.large, glow = 12.dp),
            size = CoverSize.LARGE, shape = MaterialTheme.shapes.large,
        )
        Spacer(Modifier.height(20.dp))
        Text(album.name, style = MaterialTheme.typography.headlineSmall.glow(Hud.colors.accent.copy(alpha = 0.5f)), textAlign = TextAlign.Center)
        TextButton(onClick = { album.artistId?.let(actions::openArtist) }, enabled = album.artistId != null) {
            Text(album.artistLabel, style = MaterialTheme.typography.titleMedium, color = Hud.colors.accent2)
        }
        MetaLine(listOfNotNull(album.year?.toString(), album.genre, "${album.songCount} tracks", formatTotal(album.duration)).joinToString(" // "))
        summarizeQuality(album.song)?.let { quality ->
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (anyHiRes(album.song)) HudTag("HI-RES", filled = true)
                Text(quality.uppercase(), style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent)
            }
        }
        Spacer(Modifier.height(16.dp))
        PlayShuffleButtons(onPlay = { onPlay(false) }, onShuffle = { onPlay(true) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AlbumMenu(album: Album) {
    val actions = LocalActions.current
    var open by remember { mutableStateOf(false) }
    val entries = actions.entries(album.song)
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        ActionMenu(
            open, { open = false },
            buildList {
                add(MenuAction("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { actions.playNext(entries) })
                add(MenuAction("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { actions.enqueue(entries) })
                add(MenuAction("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { actions.addToPlaylist(entries) })
                add(MenuAction("More like this", Icons.Rounded.AutoAwesome) {
                    actions.moreLike(
                        "“${album.name}”", album.artistLabel, "the album “${album.name}”" + album.artistLabel.takeIf { it.isNotEmpty() }?.let { " by $it" }.orEmpty() +
                            album.genre?.let { " ($it)" }.orEmpty(),
                        artistId = album.artistId, cover = album.coverArt,
                    )
                })
                album.artistId?.let { add(MenuAction("Go to artist", Icons.Rounded.Person) { actions.openArtist(it) }) }
                if (actions.canDeleteMusic && album.artistLabel.isNotEmpty()) {
                    add(MenuAction("Delete from server", Icons.Rounded.DeleteForever) { actions.deleteFromServer(album.artistLabel, album.name, album.year) { it.albumId == album.id } })
                }
            },
        )
    }
}
