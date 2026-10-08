package io.github.deadeyebarb.tonearm.ui.common

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.download.DownloadEntry
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.ui.theme.EqualizerBars
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import java.util.Locale

data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

enum class SongLeading { Cover, TrackNumber, None }

@Composable
fun isStarred(serverId: String?, id: String, fromServer: Boolean): Boolean =
    when {
        YouTubeMusic.isYouTube(serverId) -> id in LocalPendingLikes.current
        LocalMusic.isLocal(serverId) -> id in LocalPhoneLikes.current
        else -> serverId?.let { LocalStarOverrides.current["$it/$id"] } ?: fromServer
    }

@Composable
fun downloadOf(serverId: String?, songId: String): DownloadEntry? = serverId?.let { LocalDownloads.current["$it/$songId"] }

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    serverId: String? = null,
    leading: SongLeading = SongLeading.Cover,
    showAlbum: Boolean = true,
    extraActions: List<MenuAction> = emptyList(),
) {
    val actions = LocalActions.current
    val hud = Hud.colors
    val sid = serverId ?: actions.activeServerId
    val now = LocalNowPlaying.current
    val isCurrent = now != null && now.first == sid && now.second == song.id
    val playing = LocalPlaying.current
    val download = downloadOf(sid, song.id)
    val fetch = rememberFetchState(song, sid)
    val disliked = rememberDisliked(song)
    ListItem(
        modifier = modifier
            .clickable(onClick = onClick)
            .alpha(if (disliked && !isCurrent) 0.45f else 1f)
            .then(
                if (isCurrent) {
                    Modifier.drawBehind {
                        drawRect(Brush.horizontalGradient(listOf(hud.accent.copy(alpha = 0.14f), Color.Transparent)))
                        drawLine(hud.accent.copy(alpha = 0.35f), Offset(0f, 0f), Offset(0f, size.height), 7.dp.toPx())
                        drawLine(hud.accent, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx())
                    }
                } else {
                    Modifier
                },
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = when (leading) {
            SongLeading.Cover -> ({
                Box(contentAlignment = Alignment.Center) {
                    CoverArt(
                        song.coverArt,
                        Modifier.size(48.dp).border(1.dp, if (isCurrent) hud.accent else hud.line, MaterialTheme.shapes.small),
                        serverId = sid, size = CoverSize.THUMB,
                    )
                    if (isCurrent) {
                        Box(Modifier.size(48.dp).background(hud.void.copy(alpha = 0.55f), MaterialTheme.shapes.small))
                        EqualizerBars(playing)
                    }
                }
            })
            SongLeading.TrackNumber -> ({
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    if (isCurrent) {
                        EqualizerBars(playing)
                    } else {
                        Text(
                            song.track?.let { String.format(Locale.ROOT, "%02d", it) } ?: "--",
                            style = MaterialTheme.typography.labelMedium, color = hud.dim, textAlign = TextAlign.Center,
                        )
                    }
                }
            })
            SongLeading.None -> null
        },
        headlineContent = {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                color = if (isCurrent) hud.accent else hud.text,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isLossless(song.suffix) && isHiRes(song.bitDepth, song.samplingRate)) HudTag("HI-RES")
                if (download?.completed == true) {
                    Icon(Icons.Rounded.DownloadDone, "Downloaded", Modifier.size(14.dp), tint = hud.ok)
                }
                FetchIcon(fetch)
                Text(
                    listOfNotNull(song.artistLabel.ifEmpty { null }, song.album.takeIf { showAlbum }).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = hud.dim,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (download?.active == true) {
                    CircularProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                song.duration?.let {
                    Text(formatDuration(it.toLong()), style = MaterialTheme.typography.labelMedium, color = hud.dim)
                }
                SongMenuButton(song, sid, extraActions)
            }
        },
    )
}

@Composable
fun SongMenuButton(song: Song, serverId: String?, extraActions: List<MenuAction> = emptyList()) {
    val actions = LocalActions.current
    var open by remember { mutableStateOf(false) }
    val entry = serverId?.let { QueueSong(it, song) } ?: return
    val starred = isStarred(serverId, song.id, song.starred != null)
    val download = downloadOf(serverId, song.id)
    val sameServer = serverId == actions.activeServerId
    val disliked = rememberDisliked(song)
    val canDislike = rememberCanDislike()
    val items = buildList {
        add(MenuAction("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { actions.playNext(listOf(entry)) })
        add(MenuAction("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { actions.enqueue(listOf(entry)) })
        if (sameServer) add(MenuAction("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { actions.addToPlaylist(listOf(entry)) })
        add(MenuAction(if (starred) "Unlike" else "Like", if (starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { actions.setLiked(entry, !starred) })
        if (canDislike) {
            add(MenuAction(if (disliked) "Remove dislike" else "Dislike", if (disliked) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown) { actions.setDisliked(entry, !disliked) })
        }
        // A YouTube Music song or one stored on the phone isn't on any server: nothing to download.
        if (!LocalMusic.isLocal(serverId)) {
            if (download == null || download.failed) {
                add(MenuAction("Download", Icons.Rounded.Download) { actions.download(listOf(entry)) })
            } else {
                add(MenuAction("Remove download", Icons.Rounded.RemoveCircleOutline) { actions.removeDownloads(listOf(entry)) })
            }
        }
        add(MenuAction("More like this", Icons.Rounded.AutoAwesome) { actions.moreLikeSong(entry) })
        if (sameServer) {
            add(MenuAction("Instant mix", Icons.Rounded.Radio) { actions.instantMix(entry) })
            song.albumId?.let { add(MenuAction("Go to album", Icons.Rounded.Album) { actions.openAlbum(it) }) }
            song.artistId?.let { add(MenuAction("Go to artist", Icons.Rounded.Person) { actions.openArtist(it) }) }
            if (actions.canDeleteMusic) add(MenuAction("Delete from server", Icons.Rounded.DeleteForever) { actions.deleteFromServer(entry) })
        }
        addAll(extraActions)
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Hud.colors.dim) }
        ActionMenu(open, { open = false }, items)
    }
}

@Composable
fun ActionMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuAction>) {
    val hud = Hud.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = hud.panelHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, hud.accent.copy(alpha = 0.4f)),
    ) {
        for (item in items) {
            DropdownMenuItem(
                text = { Text(item.label, style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(item.icon, null, tint = hud.accent) },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
            )
        }
    }
}

/** Kept for call sites that just need a small label. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, emphasized: Boolean = true) {
    HudTag(text, modifier, color = if (emphasized) Hud.colors.accent else Hud.colors.dim)
}

@Composable
fun AlbumCard(album: Album, modifier: Modifier = Modifier, serverId: String? = null, onClick: () -> Unit) {
    val hud = Hud.colors
    Column(modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(6.dp)) {
        CoverArt(
            album.coverArt,
            Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, hud.accent.copy(alpha = 0.22f), MaterialTheme.shapes.medium),
            serverId = serverId, size = CoverSize.CARD, shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = hud.text)
        Text(
            listOfNotNull(album.artistLabel.ifEmpty { null }, album.year?.toString()).joinToString(" // ").uppercase(),
            style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AlbumRow(albums: List<Album>, onClick: (Album) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
        items(albums, key = { it.id }) { album -> AlbumCard(album, Modifier.width(156.dp)) { onClick(album) } }
    }
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    val hud = Hud.colors
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            CoverArt(
                artist.coverArt, Modifier.size(48.dp).border(1.dp, hud.accent.copy(alpha = 0.4f), CircleShape),
                size = CoverSize.THUMB, shape = CircleShape, placeholder = Icons.Rounded.Person,
            )
        },
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(if (artist.albumCount == 1) "1 ALBUM" else "${artist.albumCount} ALBUMS", style = MaterialTheme.typography.labelSmall, color = hud.dim)
        },
    )
}

@Composable
fun ArtistCircle(artist: Artist, onClick: () -> Unit) {
    val hud = Hud.colors
    Column(
        Modifier.width(120.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(
            artist.coverArt, Modifier.size(104.dp).glowBorder(hud.accent.copy(alpha = 0.6f), CircleShape, glow = 6.dp),
            size = CoverSize.CARD, shape = CircleShape, placeholder = Icons.Rounded.Person,
        )
        Spacer(Modifier.height(8.dp))
        Text(artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    val hud = Hud.colors
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            CoverArt(
                playlist.coverArt, Modifier.size(56.dp).border(1.dp, hud.line, MaterialTheme.shapes.small),
                size = CoverSize.THUMB, placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
            )
        },
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(
                listOfNotNull("${playlist.songCount} TRACKS", formatTotal(playlist.duration).uppercase(), playlist.owner?.uppercase()).joinToString(" // "),
                style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1,
            )
        },
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) =
    HudSectionHeader(title, modifier, onSeeAll)

@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HudButton("Play", onPlay, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
        HudButton("Shuffle", onShuffle, Modifier.weight(1f), icon = Icons.Rounded.Shuffle, filled = false)
    }
}

/** Download button for a whole album or playlist: shows progress, and offers removal once done. */
@Composable
fun DownloadAllButton(items: List<QueueSong>) {
    val actions = LocalActions.current
    val downloads = LocalDownloads.current
    val states = items.map { downloads["${it.serverId}/${it.song.id}"] }
    var confirmRemove by remember { mutableStateOf(false) }
    when {
        items.isEmpty() -> Unit
        states.all { it?.completed == true } ->
            IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Hud.colors.ok) }
        states.any { it?.active == true } -> {
            val progress = states.sumOf { (it?.percent ?: 0f).toDouble() } / items.size / 100
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            }
        }
        else -> IconButton(onClick = { actions.download(items.filter { downloads["${it.serverId}/${it.song.id}"]?.completed != true }) }) {
            Icon(Icons.Rounded.Download, "Download")
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove downloads?") },
            text = { Text("${items.size} songs will no longer be available offline.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    actions.removeDownloads(items)
                }) { Text("REMOVE") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
fun StarButton(serverId: String?, kind: StarKind, id: String, fromServer: Boolean) {
    val actions = LocalActions.current
    val hud = Hud.colors
    val starred = isStarred(serverId, id, fromServer)
    IconButton(onClick = { serverId?.let { actions.setStarred(it, kind, id, !starred) } }) {
        Icon(
            if (starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (starred) "Unlike" else "Like",
            tint = if (starred) hud.accent2 else hud.dim,
        )
    }
}

/** The like button for a song, library or YouTube Music. */
/** Whether the user disliked [song]; follows changes. */
@Composable
fun rememberDisliked(song: Song): Boolean {
    val c = LocalContext.current.container
    val state by c.dislikes.state.collectAsStateWithLifecycle()
    return remember(state, song.id) { c.dislikes.isDisliked(song.artist, song.title) }
}

/** Whether songs can be disliked, following the Tonearm server as it's found. */
@Composable
fun rememberCanDislike(): Boolean {
    val c = LocalContext.current.container
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    return server?.dislikes == true
}

@Composable
fun DislikeButton(entry: QueueSong, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val disliked = rememberDisliked(entry.song)
    IconButton(onClick = { actions.setDisliked(entry, !disliked) }, modifier = modifier) {
        Icon(
            if (disliked) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown,
            if (disliked) "Remove dislike" else "Dislike",
            tint = if (disliked) Hud.colors.danger else Hud.colors.dim,
        )
    }
}

@Composable
fun LikeButton(entry: QueueSong, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val hud = Hud.colors
    val liked = isStarred(entry.serverId, entry.song.id, entry.song.starred != null)
    IconButton(onClick = { actions.setLiked(entry, !liked) }, modifier = modifier) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) hud.accent2 else hud.dim,
        )
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "OK",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel.uppercase()) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}
