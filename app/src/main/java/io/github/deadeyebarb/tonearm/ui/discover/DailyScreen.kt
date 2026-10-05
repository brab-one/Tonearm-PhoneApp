package io.github.deadeyebarb.tonearm.ui.discover

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.DailyMissing
import io.github.deadeyebarb.tonearm.integrations.DailyMix
import io.github.deadeyebarb.tonearm.integrations.Recommender
import io.github.deadeyebarb.tonearm.ui.DailyRoute
import io.github.deadeyebarb.tonearm.ui.LidarrSettingsRoute
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.YouTubePlayButton
import io.github.deadeyebarb.tonearm.ui.common.formatTotal
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.request.RemoteCover
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudPanel
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.Orbitron
import io.github.deadeyebarb.tonearm.ui.theme.cornerBrackets
import io.github.deadeyebarb.tonearm.ui.theme.glow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MONITOR_CHOICES = listOf(
    "latest" to "Latest album of each artist (recommended)",
    "all" to "All albums",
    "none" to "Just add the artists, download nothing yet",
)

@Composable
fun DailyScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val session by c.sessions.active.collectAsStateWithLifecycle()
    val live by c.daily.state.collectAsStateWithLifecycle()
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    val vm = rememberLoader("daily", session?.id, LocalDate.now()) { c.daily.ensureToday() }
    var confirmRequestAll by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }

    DetailScaffold(
        title = "Daily Discovery",
        actions = {
            IconButton(onClick = { actions.launch { c.daily.ensureToday(force = true); vm.reload(silent = true) } }) {
                Icon(Icons.Rounded.Refresh, "Rebuild today's mix")
            }
        },
    ) {
        LoadContent(vm) { loaded ->
            val mix = live?.takeIf { it.date == loaded.date && it.serverId == loaded.serverId } ?: loaded
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    DailyHeader(
                        mix,
                        onPlay = { shuffle -> actions.playEntries(mix.entries, shuffle = shuffle) },
                        onRequestAll = {
                            if (integrations.lidarr == null) actions.navigate(LidarrSettingsRoute) else confirmRequestAll = true
                        },
                        requesting = requesting,
                    )
                }
                item { HudSectionHeader("Today's tracks") }
                if (mix.songs.isEmpty()) {
                    item {
                        Text(
                            "Nothing in your library matches yet. Request the artists below and they'll show up here once Lidarr has fetched them.",
                            style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                itemsIndexed(mix.songs, key = { i, s -> "$i:${s.id}" }) { i, song ->
                    SongRow(song, serverId = mix.serverId, onClick = { actions.playEntries(mix.entries, i) })
                }
                if (mix.missing.isNotEmpty()) {
                    item { HudSectionHeader("Not in your library") }
                    items(mix.missing, key = { "m:" + it.name }) { missing ->
                        val done = Recommender.normalize(missing.name).let { it in mix.requested || it in actions.requestedArtists }
                        MissingDailyRow(missing, done) { actions.requestArtist(missing.name) }
                    }
                }
                item {
                    Text(
                        "A new mix arrives every day. It's also saved on your server as the “Daily Discovery” playlist, so other apps and Android Auto can play it.",
                        style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (confirmRequestAll) {
                RequestAllDialog(
                    count = mix.toRequest.size,
                    onConfirm = { monitor ->
                        confirmRequestAll = false
                        requesting = true
                        actions.launch {
                            try {
                                val result = c.daily.requestAll(monitor)
                                actions.message(
                                    buildString {
                                        append("Requested ${result.requested} artist" + if (result.requested == 1) "" else "s")
                                        if (result.alreadyThere > 0) append(", ${result.alreadyThere} already in Lidarr")
                                        if (result.notFound > 0) append(", ${result.notFound} not found")
                                        if (result.failed > 0) append(", ${result.failed} failed")
                                    },
                                )
                            } finally {
                                requesting = false
                            }
                        }
                    },
                    onDismiss = { confirmRequestAll = false },
                )
            }
        }
    }
}

@Composable
private fun DailyHeader(mix: DailyMix, onPlay: (Boolean) -> Unit, onRequestAll: () -> Unit, requesting: Boolean) {
    val hud = Hud.colors
    val date = LocalDate.parse(mix.date)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Collage(mix, Modifier.size(132.dp))
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEE", locale)).uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = hud.accent,
                )
                Text(
                    date.format(DateTimeFormatter.ofPattern("d MMM", locale)).uppercase(),
                    style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 30.sp).glow(hud.accent.copy(alpha = 0.6f), 20f),
                )
                Text(
                    "${mix.songs.size} TRACKS // ${formatTotal(mix.songs.sumOf { it.duration ?: 0 }).uppercase()}",
                    style = MaterialTheme.typography.labelSmall, color = hud.dim,
                )
                if (mix.missing.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HudTag("${mix.toRequest.size} TO REQUEST", color = hud.accent2)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudButton("Play", { onPlay(false) }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, enabled = mix.songs.isNotEmpty())
            HudButton(
                if (requesting) "Requesting…" else "Request all (${mix.toRequest.size})", onRequestAll, Modifier.weight(1.3f),
                icon = Icons.Rounded.CloudDownload, filled = false, enabled = !requesting && mix.toRequest.isNotEmpty(),
            )
        }
    }
}

/** 2×2 grid of the first covers in the mix, in a bracketed frame. */
@Composable
private fun Collage(mix: DailyMix, modifier: Modifier) {
    val covers = mix.songs.mapNotNull { it.coverArt }.distinct().take(4)
    val shape = MaterialTheme.shapes.medium
    Box(modifier.cornerBrackets(Hud.colors.accent, inset = 5.dp).clip(shape).background(Hud.colors.panel)) {
        Column {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (col in 0 until 2) {
                        CoverArt(
                            covers.getOrNull((row * 2 + col) % covers.size.coerceAtLeast(1)),
                            Modifier.weight(1f).fillMaxSize(), serverId = mix.serverId, size = CoverSize.THUMB,
                            shape = androidx.compose.ui.graphics.RectangleShape,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, Hud.colors.void.copy(alpha = 0.6f)))))
    }
}

@Composable
private fun MissingDailyRow(missing: DailyMissing, requested: Boolean, onRequest: () -> Unit) {
    val hud = Hud.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteCover(missing.imageUrl, Icons.Rounded.Person, Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(missing.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(missing.reason.uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        YouTubePlayButton(missing.name)
        if (requested) HudTag("REQUESTED", color = hud.ok, filled = true) else HudButton("Request", onRequest, filled = false)
    }
}

@Composable
private fun RequestAllDialog(count: Int, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf("latest") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request $count artist" + if (count == 1) "?" else "s?") },
        text = {
            Column {
                Text("Lidarr adds each artist and downloads:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                for ((value, label) in MONITOR_CHOICES) {
                    Row(
                        Modifier.fillMaxWidth().selectable(choice == value, role = Role.RadioButton) { choice = value }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == value, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text("REQUEST ALL") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

/** Today's Daily Discovery as a card at the top of Discover. */
@Composable
fun DailyCard(actions: AppActions) {
    val c = LocalContext.current.container
    val mix by c.daily.state.collectAsStateWithLifecycle()
    val hud = Hud.colors
    val today = LocalDate.now().toString()
    androidx.compose.runtime.LaunchedEffect(today) {
        // Cheap when today's mix is already stored; rebuilds it otherwise (or when it came out empty).
        runCatching { c.daily.ensureToday() }
    }
    val current = mix?.takeIf { it.date == today }
    HudPanel(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { actions.navigate(DailyRoute) },
        glow = true,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current != null) Collage(current, Modifier.size(72.dp)) else Box(Modifier.size(72.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("DAILY DISCOVERY", style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp))
                Text(
                    current?.let { "${it.songs.size} TRACKS // ${it.toRequest.size} TO REQUEST" } ?: "BUILDING TODAY'S MIX…",
                    style = MaterialTheme.typography.labelSmall, color = hud.accent,
                )
                Text("Fresh picks every day", style = MaterialTheme.typography.bodySmall, color = hud.dim)
            }
            if (current != null) {
                IconButton(onClick = { actions.playEntries(current.entries) }) {
                    Box(Modifier.size(42.dp).border(1.5.dp, hud.accent, CircleShape).background(hud.accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "Play Daily Discovery", tint = hud.accent)
                    }
                }
            }
        }
    }
}
