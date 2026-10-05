package io.github.deadeyebarb.tonearm.ui.player

import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.ui.common.LikeButton
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.SongExtras
import io.github.deadeyebarb.tonearm.media.serverId
import io.github.deadeyebarb.tonearm.playback.PlayerUiState
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit) {
    val item = state.current ?: return
    val hud = Hud.colors
    val player = LocalActions.current.player
    val settings by LocalContext.current.container.settings.state.collectAsStateWithLifecycle()
    val position by rememberPosition(state)
    val visualizer = rememberVisualizerState(playing = state.isPlaying, enabled = settings.visualizer)
    val metadata = item.mediaMetadata
    val progress = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val shape = CutCornerShape(topStart = 14.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .glowBorder(hud.accent.copy(alpha = 0.55f), shape, glow = 6.dp)
            .background(Brush.horizontalGradient(listOf(hud.panelHigh.copy(alpha = 0.97f), hud.panel.copy(alpha = 0.97f))), shape)
            .drawBehind {
                // Progress runs along the top edge as a glowing line.
                val x = size.width * progress
                drawLine(hud.accent.copy(alpha = 0.3f), Offset(0f, 0f), Offset(x, 0f), 6.dp.toPx())
                drawLine(hud.accent, Offset(0f, 0f), Offset(x, 0f), 2.dp.toPx())
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total < -120) player.next() else if (total > 120) player.previous() },
                    ) { _, dx -> total += dx }
                }
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                metadata.extras?.getString(SongExtras.COVER),
                Modifier.size(46.dp).border(1.dp, hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.small),
                serverId = item.serverId, size = CoverSize.THUMB, shape = MaterialTheme.shapes.small,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    metadata.title?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, modifier = Modifier.basicMarquee(), color = hud.text,
                )
                Text(
                    metadata.artist?.toString().orEmpty().uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = hud.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            MiniSpectrum(visualizer)
            item.toQueueSong()?.let { LikeButton(it) }
            IconButton(onClick = player::togglePlayPause) {
                Box(
                    Modifier.size(38.dp).border(1.5.dp, hud.accent, CircleShape).background(hud.accent.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.buffering && state.playWhenReady) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = hud.accent)
                    } else {
                        Icon(
                            if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.playWhenReady) "Pause" else "Play", tint = hud.accent,
                        )
                    }
                }
            }
            IconButton(onClick = player::next) { Icon(Icons.Rounded.SkipNext, "Next", tint = hud.text) }
        }
    }
    Spacer(Modifier.height(6.dp).background(Color.Transparent))
}
