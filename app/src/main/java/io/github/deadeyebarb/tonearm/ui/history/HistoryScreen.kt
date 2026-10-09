package io.github.deadeyebarb.tonearm.ui.history

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.connect.AlbumCount
import io.github.deadeyebarb.tonearm.connect.ArtistCount
import io.github.deadeyebarb.tonearm.connect.RecentPlay
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.ListeningStats
import io.github.deadeyebarb.tonearm.integrations.Period
import io.github.deadeyebarb.tonearm.integrations.StatsPage
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.ui.common.AlbumCard
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudPanel
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.Orbitron
import io.github.deadeyebarb.tonearm.ui.theme.glow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What you played this week or month, and before: totals, top artists and albums, and every play by day. */
@Composable
fun HistoryScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val session by c.sessions.active.collectAsStateWithLifecycle()
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var offset by rememberSaveable { mutableIntStateOf(0) }

    DetailScaffold(title = "History") {
        if (server?.stats != true) {
            EmptyState(
                Icons.Rounded.History, "Your history lives on the Tonearm server",
                "The Tonearm server (1.7 or later) next to your music server keeps what you play here and on the desktop.",
            )
            return@DetailScaffold
        }
        val vm = rememberLoader("history", session?.id, period, offset) { c.stats.load(period, offset) }
        Column {
            val label = remember(period, offset) {
                ListeningStats.window(period, offset, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault()).label
            }
            PeriodBar(period, offset, label, onPeriod = { period = it; offset = 0 }, onStep = { offset += it })
            LoadContent(vm) { page -> HistoryList(page, period, offset, actions) }
        }
    }
}

@Composable
private fun PeriodBar(period: Period, offset: Int, label: String, onPeriod: (Period) -> Unit, onStep: (Int) -> Unit) {
    val hud = Hud.colors
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in Period.entries) {
            FilterChip(
                selected = p == period, onClick = { if (p != period) onPeriod(p) },
                label = { Text(p.name, style = MaterialTheme.typography.labelMedium) },
                shape = MaterialTheme.shapes.small,
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, if (period == Period.WEEK) "Week before" else "Month before") }
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = hud.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onStep(1) }, enabled = offset < 0) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, if (period == Period.WEEK) "Week after" else "Month after")
        }
    }
}

@Composable
private fun HistoryList(page: StatsPage, period: Period, offset: Int, actions: AppActions) {
    val c = LocalContext.current.container
    val listening = page.listening
    // Older plays, a page at a time, below the first page.
    var older by remember(page) { mutableStateOf(emptyList<RecentPlay>()) }
    var lastPage by remember(page) { mutableIntStateOf(listening.recent.size) }
    var loading by remember(page) { mutableStateOf(false) }
    val plays = listening.recent + older
    val zone = ZoneId.systemDefault()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Totals(listening.plays, listening.minutes) }
        if (listening.plays == 0) {
            item {
                EmptyState(
                    Icons.Rounded.History, if (period == Period.WEEK) "Nothing played that week" else "Nothing played that month",
                    "Play something and it shows up here.", Modifier.height(300.dp),
                )
            }
            return@LazyColumn
        }
        if (listening.artists.isNotEmpty()) {
            item { HudSectionHeader("Top artists") }
            val most = listening.artists.maxOf { it.plays }.coerceAtLeast(1)
            itemsIndexed(listening.artists, key = { _, a -> "artist:" + a.artist }) { i, count ->
                TopArtistRow(i + 1, count, page.artists.getOrNull(i), most, actions)
            }
        }
        if (listening.albums.isNotEmpty()) {
            item { HudSectionHeader("Top albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    itemsIndexed(listening.albums, key = { _, a -> "album:" + a.artist + "|" + a.album }) { i, count ->
                        TopAlbumCard(i + 1, count, page.albums.getOrNull(i), actions)
                    }
                }
            }
        }
        item { HudSectionHeader("Played") }
        for (day in ListeningStats.dayGroups(plays, System.currentTimeMillis(), zone)) {
            item(key = "day:" + day.label) {
                Text(
                    day.label, style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent2,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(day.plays, key = { _, p -> "play:${p.at}:${p.artist}:${p.title}" }) { _, play -> PlayRow(play, zone, actions) }
        }
        if (lastPage >= ListeningStats.PAGE) {
            item {
                HudButton(
                    if (loading) "Loading…" else "Load more",
                    {
                        loading = true
                        actions.launch {
                            try {
                                val next = c.stats.more(period, offset, before = plays.last().at)
                                older = older + next
                                lastPage = next.size
                            } finally {
                                loading = false
                            }
                        }
                    },
                    Modifier.fillMaxWidth().padding(16.dp), filled = false, enabled = !loading,
                )
            }
        }
    }
}

@Composable
private fun Totals(plays: Int, minutes: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile("Plays", plays.toString(), Modifier.weight(1f))
        Tile("Hours", String.format(Locale.getDefault(), "%.1f", minutes / 60.0), Modifier.weight(1f))
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    val hud = Hud.colors
    HudPanel(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.dim)
            Text(
                value, color = hud.text,
                style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 24.sp).glow(hud.accent.copy(alpha = 0.5f), 16f),
            )
        }
    }
}

/** "01 (cover) The Oscillators ▬▬▬▬ 42 PLAYS": the library's artist page, else the artist on YouTube Music. */
@Composable
private fun TopArtistRow(rank: Int, count: ArtistCount, artist: Artist?, most: Int, actions: AppActions) {
    val hud = Hud.colors
    Row(
        Modifier.fillMaxWidth()
            .clickable { if (artist != null) actions.openArtist(artist.id) else actions.findOnYouTube(count.artist) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(String.format(Locale.ROOT, "%02d", rank), style = MaterialTheme.typography.labelMedium, color = hud.dim, modifier = Modifier.width(26.dp))
        CoverArt(
            artist?.coverArt, Modifier.size(40.dp).border(1.dp, hud.accent.copy(alpha = 0.4f), CircleShape),
            size = CoverSize.THUMB, shape = CircleShape, placeholder = Icons.Rounded.Person,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(count.artist, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text("${count.plays} PLAYS", style = MaterialTheme.typography.labelSmall, color = hud.accent)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(count.plays.toFloat() / most).height(3.dp).clip(CircleShape).background(hud.accent.copy(alpha = 0.7f)))
        }
    }
}

/** A top album: the library's, else found on YouTube Music. */
@Composable
private fun TopAlbumCard(rank: Int, count: AlbumCount, album: Album?, actions: AppActions) {
    val hud = Hud.colors
    AlbumCard(
        album ?: Album("", count.album, count.artist), Modifier.width(140.dp),
        tag = if (album == null) ({ HudTag("Not in library", Modifier.background(hud.void.copy(alpha = 0.75f)), color = hud.accent2) }) else null,
        caption = "#$rank // ${count.plays} PLAYS" to hud.accent,
    ) { if (album != null) actions.openAlbum(album.id) else actions.openFoundAlbum(count.artist, count.album) }
}

/** "21:14  Low Hum · The Oscillators": tap plays it (the library's copy, else YouTube Music's). */
@Composable
private fun PlayRow(play: RecentPlay, zone: ZoneId, actions: AppActions) {
    val hud = Hud.colors
    val time = remember(play.at) { Instant.ofEpochMilli(play.at).atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }
    Row(
        Modifier.fillMaxWidth().clickable { actions.playFound(play.artist, play.title) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = MaterialTheme.typography.labelMedium, color = hud.dim, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(play.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = hud.text)
            Text(
                listOfNotNull(play.artist, play.album).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = hud.dim,
            )
        }
        when (play.source) {
            "youtube" -> HudTag("YT Music", color = hud.accent2)
            "local" -> HudTag("Phone", color = hud.accent2)
        }
    }
}
