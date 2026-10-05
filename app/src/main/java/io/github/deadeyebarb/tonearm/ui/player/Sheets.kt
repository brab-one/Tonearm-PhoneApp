package io.github.deadeyebarb.tonearm.ui.player

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.media.SongExtras
import io.github.deadeyebarb.tonearm.media.serverId
import io.github.deadeyebarb.tonearm.playback.PlayerUiState
import io.github.deadeyebarb.tonearm.playback.SleepTimer
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.formatDuration
import io.github.deadeyebarb.tonearm.ui.common.formatMs
import io.github.deadeyebarb.tonearm.ui.common.formatTotal
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.IOException

private data class QueueRow(val index: Int, val item: MediaItem) {
    val key = "$index:${item.mediaId}"
}

@Composable
fun QueueSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val player = LocalContext.current.container.player
    val order = if (state.shuffle) state.order else state.queue.indices.toList()
    var rows by remember(state.queue, state.order, state.shuffle) {
        mutableStateOf(order.mapNotNull { i -> state.queue.getOrNull(i)?.let { QueueRow(i, it) } })
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (rows.indexOfFirst { it.index == state.currentIndex } - 1).coerceAtLeast(0),
    )
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        rows = rows.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val totalSeconds = state.queue.sumOf { (it.mediaMetadata.durationMs ?: 0L) / 1000 }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.queue.size} songs · ${formatTotal(totalSeconds.toInt())}" + if (state.shuffle) " · shuffled" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = player::clearUpcoming) { Text("Clear upcoming") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.9f), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                ReorderableItem(reorderState, key = row.key, enabled = !state.shuffle) { dragging ->
                    val metadata = row.item.mediaMetadata
                    val isCurrent = row.index == state.currentIndex
                    ListItem(
                        modifier = Modifier.clickable { player.skipTo(row.index) },
                        tonalElevation = if (dragging) 8.dp else 0.dp,
                        colors = ListItemDefaults.colors(
                            containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                        ),
                        leadingContent = {
                            Box(contentAlignment = Alignment.Center) {
                                CoverArt(metadata.extras?.getString(SongExtras.COVER), Modifier.size(44.dp), serverId = row.item.serverId, size = CoverSize.THUMB)
                                if (isCurrent) Icon(Icons.Rounded.GraphicEq, null, tint = Color.White)
                            }
                        },
                        headlineContent = {
                            Text(
                                metadata.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                            )
                        },
                        supportingContent = { Text(metadata.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                metadata.durationMs?.let { Text(formatDuration(it / 1000), style = MaterialTheme.typography.bodySmall) }
                                if (!isCurrent) IconButton(onClick = { player.remove(row.index) }) { Icon(Icons.Rounded.Close, "Remove") }
                                if (!state.shuffle) {
                                    Icon(
                                        Icons.Rounded.DragHandle, "Reorder",
                                        Modifier.draggableHandle(onDragStopped = {
                                            val to = rows.indexOfFirst { it.key == row.key }
                                            if (to >= 0 && to != row.index) player.move(row.index, to)
                                        }).padding(12.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun LyricsSheet(entry: QueueSong, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val vm = rememberLoader("lyrics", entry.serverId, entry.song.id) {
        if (YouTubeMusic.isYouTube(entry.serverId)) throw IOException("No lyrics for songs playing from YouTube Music")
        c.api.lyrics(c.sessions.session(entry.serverId) ?: throw NoServerException(), entry.song)
    }
    val state by c.player.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(entry.song.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            entry.song.artistLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Box(Modifier.fillMaxHeight(0.85f)) {
            LoadContent(vm) { lyrics ->
                if (lyrics == null || lyrics.lines.isEmpty()) {
                    EmptyState(Icons.Rounded.Lyrics, "No lyrics", "The server has no lyrics for this song.")
                } else {
                    val position by rememberPosition(state)
                    val active = lyrics.indexAt(position)
                    val listState = rememberLazyListState()
                    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem((active - 3).coerceAtLeast(0)) }
                    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 24.dp)) {
                        itemsIndexed(lyrics.lines) { i, line ->
                            val past = lyrics.synced && i < active
                            Text(
                                line.text.ifBlank { "♪" },
                                style = when {
                                    lyrics.synced && i == active -> MaterialTheme.typography.headlineSmall.glow(Hud.colors.accent)
                                    lyrics.synced -> MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp)
                                    else -> MaterialTheme.typography.bodyLarge
                                },
                                fontWeight = if (i == active) FontWeight.Bold else null,
                                color = when {
                                    lyrics.synced && i == active -> Hud.colors.accent
                                    !lyrics.synced -> MaterialTheme.colorScheme.onSurface
                                    past -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 24.dp)
                                    .clickable(enabled = line.startMs != null) { line.startMs?.let(c.player::seekTo) }
                                    .padding(horizontal = 24.dp, vertical = if (lyrics.synced) 8.dp else 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val timer = LocalContext.current.container.sleepTimer
    val state by timer.state.collectAsStateWithLifecycle()
    val remaining by produceState(0L, state) {
        val at = state as? SleepTimer.State.At ?: return@produceState
        while (true) {
            value = at.endsAtElapsedMs - SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Sleep timer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            when (state) {
                is SleepTimer.State.At -> "Pausing in ${formatMs(remaining.coerceAtLeast(0))}"
                SleepTimer.State.EndOfTrack -> "Pausing after this song"
                SleepTimer.State.Off -> "Pause playback after a while. The last 15 seconds fade out."
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    modifier = Modifier.clickable { timer.stopAfterCurrentTrack(); onDismiss() },
                    leadingContent = { Icon(Icons.Rounded.Timer, null) },
                    headlineContent = { Text("End of this song") },
                )
            }
            itemsIndexed(listOf(5, 10, 15, 30, 45, 60, 90, 120)) { _, minutes ->
                ListItem(
                    modifier = Modifier.clickable { timer.start(minutes); onDismiss() },
                    leadingContent = { Icon(Icons.Rounded.Timer, null) },
                    headlineContent = { Text(if (minutes < 60) "$minutes minutes" else "${minutes / 60} h" + if (minutes % 60 > 0) " ${minutes % 60} min" else "") },
                )
            }
            if (state != SleepTimer.State.Off) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { timer.cancel(); onDismiss() },
                        leadingContent = { Icon(Icons.Rounded.TimerOff, null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text("Turn off", color = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
    }
}
