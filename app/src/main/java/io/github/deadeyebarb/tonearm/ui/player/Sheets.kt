package io.github.deadeyebarb.tonearm.ui.player

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.SongExtras
import io.github.deadeyebarb.tonearm.media.serverId
import io.github.deadeyebarb.tonearm.playback.PlayerUiState
import io.github.deadeyebarb.tonearm.playback.SleepTimer
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.formatDuration
import io.github.deadeyebarb.tonearm.ui.common.formatMs
import io.github.deadeyebarb.tonearm.ui.common.formatTotal
import io.github.deadeyebarb.tonearm.ui.common.plural
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
    // What "Clear upcoming" takes out: what's after the song playing (when shuffling, all the others).
    val upcoming = if (state.shuffle) state.queue.size - 1 else state.queue.size - state.currentIndex - 1
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${plural(state.queue.size, "song")} · ${formatTotal(totalSeconds.toInt())}" + if (state.shuffle) " · shuffled" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { if (upcoming > 3) confirmClear = true else player.clearUpcoming() }, enabled = upcoming > 0) { Text("Clear upcoming") }
        }
        val currentRow = rows.indexOfFirst { it.index == state.currentIndex }
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.9f), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { i, row ->
                ReorderableItem(reorderState, key = row.key, enabled = !state.shuffle) { dragging ->
                    val metadata = row.item.mediaMetadata
                    val isCurrent = row.index == state.currentIndex
                    // What already played is dimmed, so the upcoming songs stand out.
                    val played = i < currentRow && !dragging
                    ListItem(
                        modifier = Modifier.clickable { player.skipTo(row.index) }.alpha(if (played) 0.5f else 1f),
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
                                // The playing song can't be removed; its slot stays so the durations line up.
                                if (!isCurrent) IconButton(onClick = { player.remove(row.index) }) { Icon(Icons.Rounded.Close, "Remove") } else Spacer(Modifier.size(48.dp))
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear upcoming?") },
            text = { Text("$upcoming songs will be taken out of the queue.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    player.clearUpcoming()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
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
