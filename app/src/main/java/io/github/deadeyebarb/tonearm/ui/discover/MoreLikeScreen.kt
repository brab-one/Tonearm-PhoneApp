package io.github.deadeyebarb.tonearm.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.connect.SimilarArtist
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.likes.findSong
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.ui.MoreLikeRoute
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic

/**
 * "More like this": songs like it to pick from (play them, or put them in a playlist), similar artists (from
 * the Tonearm server's Deezer lookup, else the music server's own), and the AI for albums like it.
 */
@Composable
fun MoreLikeScreen(route: MoreLikeRoute) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    val hud = Hud.colors
    val seedSong = route.songId?.let { id ->
        QueueSong(route.songServerId ?: return@let null, Song(id = id, title = route.songTitle.orEmpty(), artist = route.songArtist, duration = route.songDuration))
    }
    val songs = rememberLoader("more-like-songs", route) {
        when {
            seedSong != null -> c.continuation.similarTo(seedSong)
            // For an album or artist: their best-known song on YouTube Music, and songs like it.
            route.artist != null -> c.youtube.artistSongs(route.artist, 1).firstOrNull()
                ?.let { QueueSong(YouTubeMusic.SOURCE_ID, it) }?.let { listOf(it) + c.continuation.similarTo(it) }.orEmpty()
            else -> emptyList()
        }
    }
    val artists = rememberLoader("more-like-artists", route.artist, server?.discovery) {
        val artist = route.artist ?: return@rememberLoader emptyList<SimilarArtist>()
        if (server?.discovery == true) c.connect.similarArtists(artist)
        // Library artists have a cover id; the others may have a picture URL from the server's agents.
        else route.artistId?.let { c.api.artistInfo(it, includeNotPresent = true) }?.similarArtist.orEmpty().map {
            SimilarArtist(it.name, imageUrl = if (it.inLibrary) it.coverArt else it.artistImageUrl?.takeIf { u -> u.startsWith("https://") }, inLibrary = it.inLibrary)
        }
    }
    val picked = remember(route) { mutableStateListOf<String>() }
    fun key(entry: QueueSong) = entry.serverId + "/" + entry.song.id
    val songList = songs.value.orEmpty()
    val chosen = songList.filter { key(it) in picked }

    DetailScaffold(title = "More like ${route.label}") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Picture(route.cover, Modifier.size(96.dp), if (route.songId == null && route.label == route.artist) CircleShape else MaterialTheme.shapes.medium)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("MORE LIKE", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                        Text(route.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        route.artist?.takeIf { it != route.label }?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = hud.accent2) }
                    }
                }
            }
            if (server?.recommendations == true) {
                item {
                    HudButton("Ask the AI for albums like it", {
                        actions.launch {
                            c.connect.aiPicks(refresh = true, seed = route.aiSeed)
                            actions.message("Asking for albums like ${route.label}; they'll be under AI picks in Discover in a few minutes")
                        }
                    }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), icon = Icons.Rounded.AutoAwesome, filled = false)
                }
            }
            item { SectionHeader("Similar songs") }
            item {
                LoadContent(songs, Modifier.fillMaxWidth()) { list ->
                    if (list.isEmpty()) {
                        Text("Found no songs like it.", style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(horizontal = 16.dp))
                    } else {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HudButton(if (chosen.isEmpty()) "Play all" else "Play ${chosen.size}", {
                                    actions.playEntries(listOfNotNull(seedSong) + chosen.ifEmpty { list }, 0)
                                }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                                HudButton("To playlist", { addToPlaylist(chosen.ifEmpty { list }, actions, c) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.PlaylistAdd, filled = false)
                            }
                            Text(
                                if (chosen.isEmpty()) "Tick songs to pick them; without picks, all of them go." else "${chosen.size} of ${list.size} picked",
                                style = MaterialTheme.typography.labelSmall, color = hud.dim,
                            )
                        }
                    }
                }
            }
            items(songList, key = { "song:" + key(it) }) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(key(entry) in picked, { on -> if (on) picked.add(key(entry)) else picked.remove(key(entry)) }, colors = CheckboxDefaults.colors(checkedColor = hud.accent))
                    Box(Modifier.weight(1f)) {
                        SongRow(entry.song, serverId = entry.serverId, showAlbum = false, onClick = { actions.playEntries(songList, songList.indexOf(entry).coerceAtLeast(0)) })
                    }
                }
            }
            item { SectionHeader("Similar artists") }
            item {
                LoadContent(artists, Modifier.fillMaxWidth()) { similar ->
                    if (similar.isEmpty()) {
                        Text(
                            if (route.artist == null) "No artist to go by." else "None found. The Tonearm server finds them on Deezer; without it, Navidrome needs its Last.fm agent.",
                            style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            items(artists.value.orEmpty(), key = { "artist:" + it.artist }) { artist -> SimilarRow(artist, canRequest = integrations.lidarr != null, actions = actions) }
        }
    }
}

/** Songs into a playlist: server playlists only hold the server's songs, so YouTube Music ones go in as the library's copy, if it has one. */
private fun addToPlaylist(entries: List<QueueSong>, actions: AppActions, c: AppContainer) = actions.launch {
    val session = c.sessions.active.value ?: return@launch
    val own = entries.mapNotNull { entry ->
        if (!YouTubeMusic.isYouTube(entry.serverId)) entry
        else runCatching { c.api.findSong(TrackRef(entry.song.title, entry.song.artist.orEmpty(), duration = entry.song.duration), session) }.getOrNull()?.let { QueueSong(session.id, it) }
    }
    val missing = entries.size - own.size
    if (own.isEmpty()) return@launch actions.message("None of these are on your server yet; like one to get it")
    if (missing > 0) actions.message("$missing aren't on your server yet and stay out; like them to get them")
    actions.addToPlaylist(own)
}

/** A picture by cover id (on the music server) or by URL. */
@Composable
private fun Picture(cover: String?, modifier: Modifier, shape: Shape, placeholder: ImageVector = Icons.Rounded.Person) =
    CoverArt(cover, modifier, serverId = if (cover?.startsWith("http") == true) YouTubeMusic.SOURCE_ID else null, shape = shape, placeholder = placeholder)

@Composable
private fun SimilarRow(artist: SimilarArtist, canRequest: Boolean, actions: AppActions) {
    val c = LocalContext.current.container
    val hud = Hud.colors
    // Ones in the library open there; the rest on YouTube Music.
    val open = Modifier.clickable {
        actions.launch {
            val key = Names.normalize(artist.artist)
            val id = if (!artist.inLibrary) null
            else c.api.search(artist.artist, artistCount = 10, albumCount = 0, songCount = 0).artist.firstOrNull { Names.normalize(it.name) == key }?.id
            if (id != null) actions.openArtist(id) else actions.findOnYouTube(artist.artist)
        }
    }
    Row(open.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Picture(artist.imageUrl, Modifier.size(52.dp), CircleShape)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.artist, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (artist.inLibrary) "IN YOUR LIBRARY" else "YOUTUBE MUSIC", style = MaterialTheme.typography.labelSmall, color = if (artist.inLibrary) hud.ok else hud.accent2)
        }
        if (!artist.inLibrary && canRequest) HudButton("Request", { actions.requestArtistByName(artist.artist) }, filled = false)
    }
}
