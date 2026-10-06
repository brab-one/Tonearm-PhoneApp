package io.github.deadeyebarb.tonearm.ui.player

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.Fetch
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.playback.AudioFormatInfo
import io.github.deadeyebarb.tonearm.playback.PlayerUiState
import io.github.deadeyebarb.tonearm.playback.SleepTimer
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.ui.ConnectRoute
import io.github.deadeyebarb.tonearm.ui.EqualizerRoute
import io.github.deadeyebarb.tonearm.ui.common.ActionMenu
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.LikeButton
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.MenuAction
import io.github.deadeyebarb.tonearm.ui.common.StarButton
import io.github.deadeyebarb.tonearm.ui.common.downloadOf
import io.github.deadeyebarb.tonearm.ui.common.isHiRes
import io.github.deadeyebarb.tonearm.ui.common.isLossless
import io.github.deadeyebarb.tonearm.ui.common.khz
import io.github.deadeyebarb.tonearm.ui.common.label
import io.github.deadeyebarb.tonearm.ui.common.rememberFetchState
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudBackground
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.Orbitron
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic

private enum class Sheet { None, Queue, Lyrics, Sleep }

@Composable
fun NowPlayingScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val state by c.player.state.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val item = state.current
    if (item == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val entry = remember(item) { item.toQueueSong() }
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    val visualizer = rememberVisualizerState(playing = state.isPlaying, enabled = settings.visualizer)

    Box(Modifier.fillMaxSize()) {
        Backdrop(entry)
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val header = @Composable { Header(entry, onBack, onSleep = { sheet = Sheet.Sleep }) }
            val reactor = @Composable { modifier: Modifier ->
                Reactor(entry?.song?.coverArt, entry?.serverId, visualizer, modifier)
            }
            val controls = @Composable {
                TrackInfo(entry)
                Spacer(Modifier.height(10.dp))
                Readout(entry, state.format)
                state.error?.let { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = Hud.colors.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = c.player::retry) { Text("RETRY") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val position by rememberPosition(state)
                HudSeekBar(position, state.durationMs, onSeek = c.player::seekTo)
                Spacer(Modifier.height(4.dp))
                Controls(state, visualizer)
                Spacer(Modifier.height(10.dp))
                BottomActions(onLyrics = { sheet = Sheet.Lyrics }, onSleep = { sheet = Sheet.Sleep }, onQueue = { sheet = Sheet.Queue })
            }
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    reactor(Modifier.fillMaxHeight().weight(0.9f))
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        header()
                        controls()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                    header()
                    reactor(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
                    controls()
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    when (sheet) {
        Sheet.Queue -> QueueSheet(state) { sheet = Sheet.None }
        Sheet.Lyrics -> entry?.let { LyricsSheet(it) { sheet = Sheet.None } }
        Sheet.Sleep -> SleepTimerSheet { sheet = Sheet.None }
        Sheet.None -> Unit
    }
}

/** Blurred cover art over the HUD grid (Android 12+); a palette-tinted glow elsewhere. */
@Composable
private fun Backdrop(entry: QueueSong?) {
    val hud = Hud.colors
    val tint = rememberArtworkColor(entry?.serverId, entry?.song?.coverArt) ?: hud.accent
    val animated by animateColorAsState(tint, tween(800), label = "tint")
    HudBackground()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        CoverArt(
            entry?.song?.coverArt,
            Modifier.fillMaxSize().blur(70.dp).alpha(0.38f),
            serverId = entry?.serverId, size = CoverSize.THUMB, shape = RectangleShape,
        )
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to animated.copy(alpha = 0.28f).compositeOver(Color.Transparent),
                0.45f to hud.void.copy(alpha = 0.55f),
                1f to hud.void.copy(alpha = 0.95f),
            ),
        ),
    )
}

@Composable
private fun Header(entry: QueueSong?, onBack: () -> Unit, onSleep: () -> Unit) {
    val actions = LocalActions.current
    val hud = Hud.colors
    var menu by remember { mutableStateOf(false) }
    val song = entry?.song
    val sameServer = entry?.serverId == actions.activeServerId
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "◢ NOW PLAYING ◣", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp).glow(hud.accent, 12f),
                color = hud.accent,
            )
            Text(
                song?.album.orEmpty().uppercase(), style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = hud.text,
            )
        }
        IconButton(onClick = { actions.navigate(ConnectRoute) }) { Icon(Icons.Rounded.Devices, "Play on another device") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            val items = buildList {
                if (entry != null && song != null) {
                    if (sameServer) {
                        song.albumId?.let { add(MenuAction("Go to album", Icons.Rounded.Album) { actions.openAlbum(it) }) }
                        song.artistId?.let { add(MenuAction("Go to artist", Icons.Rounded.Person) { actions.openArtist(it) }) }
                        add(MenuAction("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { actions.addToPlaylist(listOf(entry)) })
                        add(MenuAction("Instant mix", Icons.Rounded.Radio) { actions.instantMix(entry) })
                    }
                    if (!LocalMusic.isLocal(entry.serverId)) add(MenuAction("Download", Icons.Rounded.Download) { actions.download(listOf(entry)) })
                }
                add(MenuAction("Equalizer", Icons.Rounded.Equalizer) { actions.navigate(EqualizerRoute) })
                add(MenuAction("Sleep timer", Icons.Rounded.Bedtime, onSleep))
            }
            ActionMenu(menu, { menu = false }, items)
        }
    }
}

@Composable
private fun TrackInfo(entry: QueueSong?) {
    val actions = LocalActions.current
    val hud = Hud.colors
    val song = entry?.song ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                song.title.uppercase(),
                style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp).glow(hud.accent.copy(alpha = 0.6f), 22f),
                color = hud.text, maxLines = 1, modifier = Modifier.basicMarquee(),
            )
            Text(
                song.artistLabel, style = MaterialTheme.typography.titleMedium, color = hud.accent2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = song.artistId != null && entry.serverId == actions.activeServerId) {
                    song.artistId?.let(actions::openArtist)
                },
            )
        }
        LikeButton(entry)
    }
}

private fun sameCodec(codec: String, suffix: String): Boolean {
    val s = suffix.lowercase()
    val c = codec.lowercase()
    return c == s || (s in setOf("m4a", "mp4", "m4b") && c in setOf("aac", "alac")) ||
        (s in setOf("ogg", "oga") && c in setOf("vorbis", "opus", "flac")) || (s in setOf("wav", "aif", "aiff") && c == "pcm")
}

/** "[HI-RES] FLAC ▸ 24 BIT ▸ 96 KHZ ▸ 941 KBPS" */
@Composable
private fun Readout(entry: QueueSong?, format: AudioFormatInfo?) {
    val hud = Hud.colors
    val song: Song = entry?.song ?: return
    val download = downloadOf(entry.serverId, song.id)
    val transcoded = format != null && song.suffix != null && !sameCodec(format.codec, song.suffix)
    val codec = format?.codec ?: song.suffix?.uppercase()
    val depth = if (transcoded) null else format?.bitDepth ?: song.bitDepth
    val rate = format?.sampleRate ?: song.samplingRate
    val kbps = if (transcoded) format.bitrate?.div(1000) else song.bitRate
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (LocalMusic.isLocal(entry.serverId)) HudTag("ON THIS PHONE", color = hud.accent2)
        when {
            YouTubeMusic.isYouTube(entry.serverId) -> {
                HudTag("YOUTUBE MUSIC", color = hud.accent2)
                rememberFetchState(song, entry.serverId)?.let { HudTag(it.label(), color = if (it.fetch == Fetch.IN_LIBRARY) hud.ok else hud.accent) }
            }
            transcoded -> HudTag("TRANSCODED", color = hud.accent2)
            isLossless(song.suffix) && isHiRes(depth, rate) -> HudTag("HI-RES", filled = true)
            isLossless(song.suffix) -> HudTag("LOSSLESS")
        }
        if (download?.completed == true) HudTag("OFFLINE", color = hud.ok)
        Text(
            listOfNotNull(codec?.uppercase(), depth?.let { "$it BIT" }, rate?.let { "${khz(it)} KHZ" }, kbps?.takeIf { it > 0 }?.let { "$it KBPS" })
                .joinToString(" ▸ "),
            style = MaterialTheme.typography.labelMedium, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Controls(state: PlayerUiState, visualizer: VisualizerState) {
    val player = LocalActions.current.player
    val hud = Hud.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        HudToggleIcon(Icons.Rounded.Shuffle, "Shuffle", state.shuffle, onClick = { player.setShuffle(!state.shuffle) })
        IconButton(onClick = player::previous, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(38.dp), tint = hud.text)
        }
        ReactorPlayButton(
            playing = state.playWhenReady, buffering = state.buffering, visualizer = visualizer,
            icon = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (state.playWhenReady) "Pause" else "Play",
            onClick = player::togglePlayPause,
        )
        IconButton(onClick = player::next, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(38.dp), tint = hud.text)
        }
        HudToggleIcon(
            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repeat", state.repeatMode != Player.REPEAT_MODE_OFF, onClick = player::cycleRepeat,
        )
    }
}

@Composable
private fun BottomActions(onLyrics: () -> Unit, onSleep: () -> Unit, onQueue: () -> Unit) {
    val timer by LocalContext.current.container.sleepTimer.state.collectAsStateWithLifecycle()
    val hud = Hud.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        DeckButton(Icons.Rounded.Lyrics, "LYRICS", hud.dim, onLyrics)
        DeckButton(Icons.Rounded.Bedtime, if (timer != SleepTimer.State.Off) "TIMER ON" else "SLEEP", if (timer != SleepTimer.State.Off) hud.accent else hud.dim, onSleep)
        DeckButton(Icons.AutoMirrored.Rounded.QueueMusic, "QUEUE", hud.dim, onQueue)
    }
}

@Composable
private fun DeckButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    val hud = Hud.colors
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .glowBorder(hud.line, shape, glow = 0.dp)
            .clip(shape)
            .background(hud.panel.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
    }
}
