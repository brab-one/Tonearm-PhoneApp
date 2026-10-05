package io.github.deadeyebarb.tonearm.ui.discover

import android.text.format.DateUtils
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.BrainarrData
import io.github.deadeyebarb.tonearm.integrations.BrainarrPick
import io.github.deadeyebarb.tonearm.integrations.BrainarrService
import io.github.deadeyebarb.tonearm.integrations.PickStatus
import io.github.deadeyebarb.tonearm.ui.BrainarrRoute
import io.github.deadeyebarb.tonearm.ui.LidarrSettingsRoute
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.YouTubePlayButton
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.request.RemoteCover
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudPanel
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import kotlinx.coroutines.delay

/** Everything Brainarr (the AI import list in Lidarr) has picked, and a button to ask it for more. */
@Composable
fun BrainarrScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    val live by c.brainarr.data.collectAsStateWithLifecycle()
    val vm = rememberLoader("brainarr", integrations.lidarr?.url) { c.brainarr.load() }
    val asking by c.brainarr.asking.collectAsStateWithLifecycle()
    var labelling by remember { mutableStateOf(false) }
    val fetching = remember { mutableStateListOf<Int>() }
    // Follow downloads while the screen is open.
    val inFlight = (live ?: vm.value)?.picks?.any { it.status == PickStatus.DOWNLOADING || it.status == PickStatus.ON_DISK } == true
    LaunchedEffect(inFlight) {
        while (inFlight) {
            delay(10_000)
            vm.reload(silent = true)
        }
    }

    DetailScaffold(
        title = "Brainarr",
        actions = { IconButton(onClick = { vm.reload(silent = true) }) { Icon(Icons.Rounded.Refresh, "Refresh") } },
    ) {
        if (integrations.lidarr == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(Icons.Rounded.AutoAwesome, "Connect Lidarr", "Brainarr runs inside Lidarr, so Tonearm reads its picks from there.", Modifier.weight(1f))
                HudButton("Connect Lidarr", { actions.navigate(LidarrSettingsRoute) }, Modifier.fillMaxWidth())
            }
            return@DetailScaffold
        }
        LoadContent(vm) { loaded ->
            val data = live ?: loaded
            if (data.lists.isEmpty()) {
                EmptyState(
                    Icons.Rounded.AutoAwesome, "No Brainarr list in Lidarr",
                    "Brainarr is a Lidarr plugin that asks an AI model for music like yours. Install it in Lidarr → Settings → Plugins, " +
                        "add it under Settings → Import Lists, and its picks show up here.",
                )
                return@LoadContent
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    BrainarrHeader(
                        data,
                        asking = asking,
                        onAsk = { actions.askBrainarr() },
                        onPlay = { actions.playBrainarrMix() },
                    )
                }
                if (!data.labelled) {
                    item {
                        LabelCard(labelling) {
                            labelling = true
                            actions.launch {
                                try {
                                    c.brainarr.label()
                                    vm.reload(silent = true)
                                    actions.message("Brainarr's picks will be tagged “${BrainarrService.TAG}” from now on")
                                } finally {
                                    labelling = false
                                }
                            }
                        }
                    }
                }
                if (data.picks.isEmpty()) {
                    item {
                        Text(
                            if (data.labelled) "Nothing yet. Ask Brainarr, or wait for Lidarr's next import list sync."
                            else "Nothing to show yet. Ask Brainarr here, or add the tag above so Lidarr's own runs show up too.",
                            style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim, modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    item { HudSectionHeader("Picks") }
                    items(data.picks, key = { it.lidarrId }) { pick ->
                        PickRow(pick, fetching = pick.lidarrId in fetching, actions = actions) {
                            fetching += pick.lidarrId
                            actions.launch {
                                try {
                                    c.brainarr.getIt(pick)
                                    vm.reload(silent = true)
                                    actions.message("Lidarr is getting ${pick.name}")
                                } finally {
                                    fetching -= pick.lidarrId
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Brainarr adds its picks to Lidarr, which downloads them. They become playable once your music server has scanned them. " +
                            "Brainarr skips what it suggested before and may reuse a recent answer, so asking again right away can bring nothing new.",
                        style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrainarrHeader(data: BrainarrData, asking: String?, onAsk: () -> Unit, onPlay: () -> Unit) {
    val hud = Hud.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${data.picks.size} PICKS // ${data.inLibrary.size} IN YOUR LIBRARY", style = MaterialTheme.typography.labelMedium, color = hud.accent)
        for (list in data.lists) {
            Text(
                list.name + (list.summary.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudButton(
                if (asking != null) "Thinking…" else "Ask Brainarr", onAsk, Modifier.weight(1f),
                icon = Icons.Rounded.AutoAwesome, enabled = asking == null,
            )
            HudButton(
                "Play picks", onPlay, Modifier.weight(1f),
                icon = Icons.Rounded.PlayArrow, filled = false, enabled = data.inLibrary.isNotEmpty(),
            )
        }
        BrainarrProgress(asking)
    }
}

/** A progress bar and Lidarr's status text while Brainarr runs. */
@Composable
fun BrainarrProgress(asking: String?) {
    if (asking == null) return
    val hud = Hud.colors
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth(), color = hud.accent, trackColor = hud.line)
    Text(asking, style = MaterialTheme.typography.labelSmall, color = hud.dim, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LabelCard(labelling: Boolean, onLabel: () -> Unit) {
    val hud = Hud.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, hud.accent2.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        Text("LABEL BRAINARR'S PICKS", style = MaterialTheme.typography.labelMedium, color = hud.accent2)
        Spacer(Modifier.height(6.dp))
        Text(
            "Lidarr doesn't record which list added an artist. Tonearm can add a “${BrainarrService.TAG}” tag to your Brainarr list, " +
                "so everything it adds from now on shows up here. Picks from runs you start here show up either way.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        HudButton(if (labelling) "Adding…" else "Add the tag", onLabel, Modifier.fillMaxWidth(), filled = false, enabled = !labelling)
    }
}

@Composable
private fun PickRow(pick: BrainarrPick, fetching: Boolean, actions: AppActions, onGet: () -> Unit) {
    val hud = Hud.colors
    val library = pick.libraryArtist
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = library != null) { library?.let { actions.openArtist(it.id) } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickCover(pick, Modifier.size(48.dp), CircleShape)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pick.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusLine(pick).uppercase(), style = MaterialTheme.typography.labelSmall, color = statusColor(pick.status), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pick.genres.isNotEmpty()) {
                Text(pick.genres.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        // Not playable from the library yet: listen on YouTube Music meanwhile.
        if (pick.status != PickStatus.IN_LIBRARY) YouTubePlayButton(pick.name)
        when (pick.status) {
            PickStatus.IN_LIBRARY -> IconButton(onClick = { playArtist(actions, library!!.id) }) {
                Box(Modifier.size(36.dp).border(1.5.dp, hud.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, "Play ${pick.name}", tint = hud.accent)
                }
            }
            PickStatus.NOT_MONITORED -> HudButton(if (fetching) "…" else "Get", onGet, filled = false, enabled = !fetching)
            PickStatus.DOWNLOADING -> HudTag("${((pick.progress ?: 0f) * 100).toInt()}%", color = hud.accent)
            PickStatus.ON_DISK -> HudTag("ON DISK", color = hud.ok)
            PickStatus.WANTED -> HudTag("WANTED", color = hud.dim)
        }
    }
}

private fun statusLine(pick: BrainarrPick): String {
    val added = pick.added?.let { relativeAdded(it.toEpochMilli()) }
    val tracks = "${pick.tracksOnDisk}/${pick.tracksWanted} tracks"
    return when (pick.status) {
        PickStatus.IN_LIBRARY -> listOfNotNull("In library", added)
        PickStatus.DOWNLOADING -> listOf("Downloading", tracks)
        PickStatus.ON_DISK -> listOf("Not scanned yet", tracks)
        PickStatus.WANTED -> listOfNotNull("Searching", added)
        PickStatus.NOT_MONITORED -> listOfNotNull("Not monitored", added)
    }.joinToString(" · ")
}

/** "added 3 days ago"; a server clock a little ahead of the phone's reads as "just now", not "in 0 minutes". */
private fun relativeAdded(addedMs: Long, nowMs: Long = System.currentTimeMillis()): String =
    if (nowMs - addedMs < DateUtils.MINUTE_IN_MILLIS) {
        "added just now"
    } else {
        "added " + DateUtils.getRelativeTimeSpanString(addedMs, nowMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            .toString().lowercase()
    }

@Composable
private fun statusColor(status: PickStatus) = when (status) {
    PickStatus.IN_LIBRARY -> Hud.colors.ok
    PickStatus.DOWNLOADING, PickStatus.ON_DISK -> Hud.colors.accent
    PickStatus.WANTED -> Hud.colors.dim
    PickStatus.NOT_MONITORED -> Hud.colors.accent2
}

/** The library cover once the music server has the artist, else Lidarr's poster. */
@Composable
private fun PickCover(pick: BrainarrPick, modifier: Modifier, shape: Shape) {
    val coverArt = pick.libraryArtist?.coverArt
    if (coverArt != null) {
        CoverArt(coverArt, modifier, size = CoverSize.THUMB, shape = shape, placeholder = Icons.Rounded.Person)
    } else {
        RemoteCover(pick.imageUrl, Icons.Rounded.Person, modifier, shape)
    }
}

/** Brainarr's picks as a card on Discover; hidden when Lidarr has no Brainarr list. */
@Composable
fun BrainarrCard(actions: AppActions, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    val data by c.brainarr.data.collectAsStateWithLifecycle()
    val lidarrUrl = integrations.lidarr?.url ?: return
    LaunchedEffect(lidarrUrl) { runCatching { c.brainarr.load() } }
    val current = data?.takeIf { it.lists.isNotEmpty() } ?: return
    val hud = Hud.colors
    HudPanel(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { actions.navigate(BrainarrRoute) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PickGrid(current.picks, Modifier.size(72.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("BRAINARR", style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp))
                Text(
                    "${current.picks.size} AI PICKS // ${current.inLibrary.size} IN LIBRARY",
                    style = MaterialTheme.typography.labelSmall, color = hud.accent,
                )
                Text(
                    current.lists.first().summary.ifEmpty { "AI picks from Lidarr" },
                    style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (current.inLibrary.isNotEmpty()) {
                IconButton(onClick = { actions.playBrainarrMix() }) {
                    Box(Modifier.size(42.dp).border(1.5.dp, hud.accent, CircleShape).background(hud.accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "Play Brainarr's picks", tint = hud.accent)
                    }
                }
            }
        }
    }
}

/** Up to four pick covers in a 2×2 grid. */
@Composable
private fun PickGrid(picks: List<BrainarrPick>, modifier: Modifier) {
    val hud = Hud.colors
    val shape = MaterialTheme.shapes.medium
    Box(modifier.border(1.dp, hud.line, shape).background(hud.panel, shape), contentAlignment = Alignment.Center) {
        if (picks.isEmpty()) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = hud.accent)
        } else {
            Column(Modifier.fillMaxSize()) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f)) {
                        for (col in 0 until 2) {
                            val pick = picks.getOrNull((row * 2 + col) % picks.size)
                            if (pick != null) PickCover(pick, Modifier.weight(1f).fillMaxSize(), RectangleShape)
                        }
                    }
                }
            }
        }
    }
}
