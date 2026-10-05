package io.github.deadeyebarb.tonearm.ui.downloads

import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudTopBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.download.DownloadEntry
import io.github.deadeyebarb.tonearm.ui.common.ActionMenu
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.MenuAction
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.SongLeading
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.formatBytes

@Composable
fun DownloadsScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val entries by c.downloads.entries.collectAsStateWithLifecycle()
    val all = entries.values.toList()
    val pending = all.filterNot { it.completed }.sortedBy { it.item.song.title }
    val done = all.filter { it.completed }.sortedWith(
        compareBy({ it.item.song.album?.lowercase() }, { it.item.song.discNumber ?: 1 }, { it.item.song.track ?: 0 }, { it.item.song.title }),
    )
    val albums = done.groupBy { it.item.serverId to (it.item.song.albumId ?: it.item.song.album.orEmpty()) }
    val bytes = remember(done.size) { c.downloads.bytesUsed() }
    var menu by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            HudTopBar(
                title = "Downloads",
                subtitle = if (done.isNotEmpty()) "${done.size} TRACKS // ${formatBytes(bytes)} // OFFLINE CACHE" else "OFFLINE CACHE",
                actions = {
                    if (done.isNotEmpty()) {
                        IconButton(onClick = { actions.playEntries(done.map { it.item }, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, "Shuffle downloads") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            ActionMenu(menu, { menu = false }, listOf(MenuAction("Delete all downloads", Icons.Rounded.DeleteSweep) { confirmDeleteAll = true }))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (all.isEmpty()) {
            EmptyState(
                Icons.Rounded.DownloadForOffline, "No downloads yet",
                "Download albums, playlists or songs to listen without a connection. Files are kept in their original quality.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (pending.isNotEmpty()) {
                item { SectionHeader("In progress") }
                items(pending, key = { "p:${it.id}" }) { entry -> PendingRow(entry) }
            }
            for ((key, songs) in albums) {
                val first = songs.first().item.song
                item(key = "a:${key.first}:${key.second}") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverArt(first.coverArt, Modifier.size(48.dp), serverId = key.first, size = CoverSize.THUMB)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(first.album ?: "Unknown album", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(first.artistLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.colors.accent2, maxLines = 1)
                        }
                        IconButton(onClick = { actions.playEntries(songs.map { it.item }) }) { Icon(Icons.Rounded.PlayArrow, "Play album") }
                        IconButton(onClick = { actions.removeDownloads(songs.map { it.item }) }) { Icon(Icons.Rounded.Delete, "Delete album download") }
                    }
                }
                items(songs, key = { "d:${it.id}" }) { entry ->
                    SongRow(
                        entry.item.song, serverId = entry.item.serverId, leading = SongLeading.TrackNumber, showAlbum = false,
                        onClick = { actions.playEntries(done.map { it.item }, done.indexOf(entry)) },
                    )
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all downloads?") },
            text = { Text("${done.size} songs (${formatBytes(bytes)}) will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    c.downloads.removeAll()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PendingRow(entry: DownloadEntry) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val waitingForNetwork = entry.state == Download.STATE_QUEUED && c.downloads.manager.notMetRequirements != 0
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { CoverArt(entry.item.song.coverArt, Modifier.size(48.dp), serverId = entry.item.serverId, size = CoverSize.THUMB) },
        headlineContent = { Text(entry.item.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    when {
                        entry.failed -> "Failed — tap × and download again"
                        waitingForNetwork -> "Waiting for Wi-Fi"
                        entry.state == Download.STATE_QUEUED -> "Queued"
                        else -> "${entry.percent.toInt()}% · ${formatBytes(entry.bytes)}"
                    },
                    color = if (entry.failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                if (entry.state == Download.STATE_DOWNLOADING) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { entry.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        trailingContent = {
            IconButton(onClick = { actions.removeDownloads(listOf(entry.item)) }) { Icon(Icons.Rounded.Close, "Cancel") }
        },
    )
}
