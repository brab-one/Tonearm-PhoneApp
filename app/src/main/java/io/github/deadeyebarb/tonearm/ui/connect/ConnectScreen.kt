package io.github.deadeyebarb.tonearm.ui.connect

import io.github.deadeyebarb.tonearm.local.LocalMusic
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.connect.ConnectCommand
import io.github.deadeyebarb.tonearm.connect.ConnectDevice
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.ConnectUnavailableException
import io.github.deadeyebarb.tonearm.connect.coverServer
import io.github.deadeyebarb.tonearm.connect.toConnect
import io.github.deadeyebarb.tonearm.connect.toQueueSong
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.ui.LidarrSettingsRoute
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.ErrorState
import io.github.deadeyebarb.tonearm.ui.common.Load
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.formatDuration
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudLoader
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Tonearm Connect: the other Tonearm apps (your desktop), and a remote for the one you pick. */
@Composable
fun ConnectScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf<Load<List<ConnectDevice>>>(Load.Loading) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(integrations.lidarr?.url, refresh) {
        while (true) {
            devices = try {
                Load.Ready(c.connect.devices())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
            delay(1_500)
        }
    }

    DetailScaffold(title = "Devices") {
        if ((devices as? Load.Failed)?.error is ConnectUnavailableException) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    Icons.Rounded.Devices, "Set up Tonearm Connect",
                    "Tonearm Connect runs on the Tonearm server next to your music server (it serves everyone on it). " +
                        "The phone and the desktop app find it by themselves.",
                    Modifier.weight(1f),
                )
            }
            return@DetailScaffold
        }
        when (val state = devices) {
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HudLoader() }
            is Load.Failed -> ErrorState(state.error.userMessage(), onRetry = { refresh++ })
            is Load.Ready -> {
                if (state.value.isEmpty()) {
                    EmptyState(
                        Icons.Rounded.Computer, "No other devices",
                        "Start Tonearm on your computer (signed in to the same music server); it shows up here while it runs.",
                    )
                    return@DetailScaffold
                }
                val selected = state.value.firstOrNull { it.state.id == selectedId } ?: state.value.first()
                RemoteControl(state.value, selected, onSelect = { selectedId = it }, actions = actions)
            }
        }
    }
}

@Composable
private fun RemoteControl(devices: List<ConnectDevice>, device: ConnectDevice, onSelect: (String) -> Unit, actions: AppActions) {
    val c = LocalContext.current.container
    val hud = Hud.colors
    val session by c.sessions.active.collectAsStateWithLifecycle()
    val playback = device.state.playback
    val sameServer = device.state.server == null || session?.config?.baseUrl?.trimEnd('/') == device.state.server.trimEnd('/')
    fun send(command: ConnectCommand) = actions.launch { c.connect.send(device.state.id, command) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (devices.size > 1) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.state.id }) { d ->
                        HudTag(
                            d.state.name.uppercase(), color = if (d.state.id == device.state.id) hud.accent else hud.dim,
                            filled = d.state.id == device.state.id, modifier = Modifier.clickable { onSelect(d.state.id) },
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (device.state.kind == "phone") Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, tint = hud.accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.state.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (device.online) "ONLINE" else "LAST SEEN ${formatDuration(device.secondsSinceSeen)} AGO",
                        style = MaterialTheme.typography.labelSmall, color = if (device.online) hud.ok else hud.dim,
                    )
                }
            }
            if (!sameServer) {
                Text(
                    "This device uses a different music server (${device.state.server}), so its songs can't be moved over.",
                    style = MaterialTheme.typography.bodySmall, color = hud.accent2, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        val current = playback?.current
        if (playback == null || current == null) {
            item {
                Text(
                    "Nothing playing there.", style = MaterialTheme.typography.bodyMedium, color = hud.dim,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            item { NowPlayingPanel(current, playback, session?.id, onSeek = { send(ConnectCommand(ConnectCommand.SEEK, positionMs = it)) }) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { send(ConnectCommand(ConnectCommand.SHUFFLE, shuffle = !playback.shuffle)) }) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (playback.shuffle) hud.accent else hud.dim)
                    }
                    IconButton(onClick = { send(ConnectCommand(ConnectCommand.PREVIOUS)) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(34.dp))
                    }
                    IconButton(onClick = { send(ConnectCommand(ConnectCommand.TOGGLE)) }, modifier = Modifier.size(72.dp)) {
                        Box(Modifier.size(64.dp).background(hud.accent, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "Pause" else "Play", tint = hud.void, modifier = Modifier.size(36.dp))
                        }
                    }
                    IconButton(onClick = { send(ConnectCommand(ConnectCommand.NEXT)) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(34.dp))
                    }
                    IconButton(onClick = {
                        val next = when (playback.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
                        send(ConnectCommand(ConnectCommand.REPEAT, repeat = next))
                    }) {
                        Icon(if (playback.repeat == "one") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (playback.repeat != "off") hud.accent else hud.dim)
                    }
                }
            }
            item { VolumeRow(playback.volume) { send(ConnectCommand(ConnectCommand.VOLUME, volume = it)) } }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HudButton(
                    "Play this phone's music there", {
                        val phone = actions.player.state.value
                        val order = phone.order.ifEmpty { phone.queue.indices.toList() }
                        // Songs stored on the phone can't play elsewhere.
                        val entries = order.mapNotNull { i -> phone.queue.getOrNull(i)?.toQueueSong()?.takeUnless { LocalMusic.isLocal(it.serverId) }?.let { i to it } }
                        val queue = entries.map { it.second }
                        if (queue.isEmpty()) {
                            actions.message(if (phone.queue.isEmpty()) "Nothing is playing on this phone" else "Songs stored on this phone can only play here")
                        } else {
                            val index = entries.indexOfFirst { it.first == phone.currentIndex }.coerceAtLeast(0)
                            val position = if (entries[index].first == phone.currentIndex) actions.player.position else 0L
                            actions.launch {
                                c.connect.send(device.state.id, ConnectCommand(ConnectCommand.LOAD, queue = queue.map { it.toConnect() }, index = index, positionMs = position))
                                actions.player.pause()
                                actions.message("Playing on ${device.state.name}")
                            }
                        }
                    },
                    Modifier.fillMaxWidth(), enabled = device.online && sameServer,
                )
                HudButton(
                    "Continue on this phone", {
                        val serverId = session?.id
                        if (playback?.current == null || serverId == null) return@HudButton
                        // Files from the computer's own folders can't come along.
                        val movable = playback.queue.withIndex().filter { it.value.source != ConnectSong.LOCAL }
                        if (movable.isEmpty()) return@HudButton actions.message("That's playing from the computer's own folders")
                        val start = movable.indexOfFirst { it.index >= playback.index }.takeIf { it >= 0 } ?: 0
                        val resume = if (movable[start].index == playback.index) playback.positionAt() else 0L
                        actions.player.play(movable.map { it.value.toQueueSong(serverId) }, start, startPositionMs = resume)
                        send(ConnectCommand(ConnectCommand.PAUSE))
                    },
                    Modifier.fillMaxWidth(), filled = false, enabled = playback?.current != null && sameServer,
                )
            }
        }
        if (playback != null && playback.queue.size > 1) {
            item { HudSectionHeader("Queue there") }
            itemsIndexed(playback.queue.take(MAX_QUEUE_ROWS), key = { i, s -> "$i:${s.source}:${s.id}" }) { i, song ->
                QueueRow(song, isCurrent = i == playback.index, coverServer = song.coverServer(session?.id)) {
                    send(ConnectCommand(ConnectCommand.JUMP, index = i))
                }
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(song: ConnectSong, playback: io.github.deadeyebarb.tonearm.connect.PlaybackState, serverId: String?, onSeek: (Long) -> Unit) {
    val hud = Hud.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(playback.playing) {
        while (playback.playing) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.takeIf { it > 0 } ?: (song.duration ?: 0) * 1000L
    val position = playback.positionAt(now)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CoverArt(
            song.coverArt, Modifier.size(220.dp).border(1.dp, hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium),
            serverId = song.coverServer(serverId), size = CoverSize.LARGE, shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(16.dp))
        Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(song.artist, song.album).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = hud.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (song.source == ConnectSong.YOUTUBE) HudTag("YOUTUBE MUSIC", color = hud.accent2, modifier = Modifier.padding(top = 6.dp))
        if (song.source == ConnectSong.LOCAL) HudTag("ON THE COMPUTER", color = hud.accent2, modifier = Modifier.padding(top = 6.dp))
        Slider(
            value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent, inactiveTrackColor = hud.line),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position / 1000), style = MaterialTheme.typography.labelSmall, color = hud.dim)
            Text(formatDuration(duration / 1000), style = MaterialTheme.typography.labelSmall, color = hud.dim)
        }
    }
}

@Composable
private fun VolumeRow(volume: Int, onVolume: (Int) -> Unit) {
    val hud = Hud.colors
    var dragging by remember { mutableFloatStateOf(-1f) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeUp, "Volume", tint = hud.dim)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = if (dragging >= 0) dragging else volume / 100f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                onVolume((dragging * 100).toInt())
                dragging = -1f
            },
            colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent, inactiveTrackColor = hud.line),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QueueRow(song: ConnectSong, isCurrent: Boolean, coverServer: String?, onClick: () -> Unit) {
    val hud = Hud.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(song.coverArt, Modifier.size(40.dp), serverId = coverServer, size = CoverSize.THUMB)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, color = if (isCurrent) hud.accent else hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        song.duration?.let { Text(formatDuration(it.toLong()), style = MaterialTheme.typography.labelSmall, color = hud.dim) }
    }
}

private const val MAX_QUEUE_ROWS = 200
