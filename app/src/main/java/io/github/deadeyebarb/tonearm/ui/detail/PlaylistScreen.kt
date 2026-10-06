package io.github.deadeyebarb.tonearm.ui.detail

import androidx.compose.material.icons.rounded.FavoriteBorder
import io.github.deadeyebarb.tonearm.weekly.WeeklyBatch
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
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
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.ui.common.ActionMenu
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.cornerBrackets
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.DownloadAllButton
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.MenuAction
import io.github.deadeyebarb.tonearm.ui.common.PlayShuffleButtons
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.TextInputDialog
import io.github.deadeyebarb.tonearm.ui.common.formatTotal
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader

@Composable
fun PlaylistScreen(id: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val serverId = actions.activeServerId
    val vm = rememberLoader("playlist", serverId, id) { c.api.playlist(id) }
    val playlist = vm.value
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var keeping by remember { mutableStateOf(false) }
    val weekly = playlist?.let { WeeklyPicks.parse(it) }
    DetailScaffold(
        title = playlist?.name.orEmpty(),
        actions = {
            if (playlist != null) {
                if (weekly != null) {
                    IconButton(onClick = { keeping = true }) { Icon(Icons.Rounded.FavoriteBorder, "Like and keep this playlist", tint = Hud.colors.accent2) }
                }
                DownloadAllButton(actions.entries(playlist.entry))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    ActionMenu(
                        menu, { menu = false },
                        listOf(
                            MenuAction("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { actions.playNext(actions.entries(playlist.entry)) },
                            MenuAction("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { actions.enqueue(actions.entries(playlist.entry)) },
                            MenuAction("Rename", Icons.Rounded.Edit) { renaming = true },
                            MenuAction("Delete playlist", Icons.Rounded.Delete) { deleting = true },
                        ),
                    )
                }
            }
        },
    ) {
        LoadContent(vm) { pl ->
            val context = serverId?.let { MediaIds.playlist(it, pl.id) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { PlaylistHeader(pl, onPlay = { shuffle -> actions.play(pl.entry, shuffle = shuffle, context = context) }) }
                WeeklyPicks.parse(pl)?.let { state ->
                    item {
                        Text(
                            "This week's AI picks. When next week's arrive, this playlist and its music are deleted, except albums with a song " +
                                "you liked or put in another playlist. Tap the heart to keep all of it under a name of your own.",
                            style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (state.status == WeeklyState.READY && pl.entry.size < state.albums.size) {
                        item {
                            Text(
                                "Downloading: " + state.albums.joinToString { "${it.title} (${it.artist})" },
                                style = MaterialTheme.typography.bodySmall, color = Hud.colors.accent, modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
                if (pl.entry.isEmpty()) {
                    item {
                        EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "This playlist is empty", "Add songs from any song's menu.", Modifier.height(240.dp))
                    }
                }
                itemsIndexed(pl.entry, key = { i, s -> "$i:${s.id}" }) { i, song ->
                    SongRow(
                        song, onClick = { actions.play(pl.entry, i, context = context) },
                        extraActions = listOf(
                            MenuAction("Remove from playlist", Icons.Rounded.RemoveCircleOutline) {
                                actions.launch {
                                    c.api.removeFromPlaylist(pl.id, listOf(i))
                                    vm.reload(silent = true)
                                }
                            },
                        ),
                    )
                }
            }
        }
    }
    if (keeping && playlist != null && weekly != null) {
        TextInputDialog(
            title = "Keep this playlist",
            label = "Name",
            initial = playlist.name.replace("Weekly picks", "Picks"),
            confirmLabel = "Keep",
            onConfirm = { name ->
                keeping = false
                actions.launch {
                    val session = c.sessions.active.value ?: return@launch
                    c.weekly.keep(session, WeeklyBatch(playlist, weekly), name)
                    vm.reload(silent = true)
                    actions.message("Kept as “${name.trim()}”")
                }
            },
            onDismiss = { keeping = false },
        )
    }
    if (renaming && playlist != null) {
        TextInputDialog(
            title = "Rename playlist", label = "Name", initial = playlist.name, confirmLabel = "Rename",
            onConfirm = { name ->
                renaming = false
                actions.launch {
                    c.api.renamePlaylist(playlist.id, name)
                    vm.mutate { it.copy(name = name) }
                }
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting && playlist != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${playlist.name}”?") },
            text = { Text("This removes the playlist from the server. The songs stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    actions.launch {
                        c.api.deletePlaylist(playlist.id)
                        actions.message("Playlist deleted")
                        actions.back()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaylistHeader(playlist: Playlist, onPlay: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        CoverArt(
            playlist.coverArt,
            Modifier.size(220.dp).cornerBrackets(Hud.colors.accent).glowBorder(Hud.colors.accent.copy(alpha = 0.55f), MaterialTheme.shapes.large, glow = 12.dp),
            size = CoverSize.LARGE, shape = MaterialTheme.shapes.large, placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
        )
        Spacer(Modifier.height(20.dp))
        Text(playlist.name, style = MaterialTheme.typography.headlineSmall.glow(Hud.colors.accent.copy(alpha = 0.5f)), textAlign = TextAlign.Center)
        playlist.comment?.takeIf { it.isNotBlank() }?.let { MetaLine(it) }
        MetaLine(listOfNotNull("${playlist.songCount} tracks", formatTotal(playlist.duration), playlist.owner?.let { "by $it" }).joinToString(" // "))
        Spacer(Modifier.height(16.dp))
        if (playlist.entry.isNotEmpty()) PlayShuffleButtons(onPlay = { onPlay(false) }, onShuffle = { onPlay(true) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}
