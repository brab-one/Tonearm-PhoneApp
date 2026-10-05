package io.github.deadeyebarb.tonearm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.TextInputDialog

@Composable
fun PlaylistPickerDialog(items: List<QueueSong>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val api = actions.container.api
    val playlists by produceState<List<Playlist>?>(null) { value = runCatching { api.playlists() }.getOrDefault(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    val ids = items.map { it.song.id }

    if (creating) {
        TextInputDialog(
            title = "New playlist", label = "Name", confirmLabel = "Create",
            onConfirm = { name ->
                onDismiss()
                actions.launch {
                    api.createPlaylist(name, ids)
                    actions.message("Created “$name”")
                }
            },
            onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { creating = true },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("New playlist", color = MaterialTheme.colorScheme.primary) },
                    )
                }
                val list = playlists
                if (list == null) {
                    item { CircularProgressIndicator() }
                } else {
                    items(list, key = { it.id }) { playlist ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onDismiss()
                                actions.launch {
                                    api.addToPlaylist(playlist.id, ids)
                                    actions.message("Added to “${playlist.name}”")
                                }
                            },
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text("${playlist.songCount} songs") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
