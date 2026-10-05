package io.github.deadeyebarb.tonearm.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.playback.SpectrumAnalyzer
import io.github.deadeyebarb.tonearm.ui.common.CoverArt
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.formatMs
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Smoothed spectrum bands plus a frame clock, shared by everything drawn on one screen. */
@Stable
class VisualizerState {
    val bands = FloatArray(SpectrumAnalyzer.BANDS)
    var frame by mutableLongStateOf(0L)
    var energy by mutableFloatStateOf(0f)
    var rotation by mutableFloatStateOf(0f)
}

/**
 * Runs the animation loop while [playing]: reads the analyzer, applies a fast-attack /
 * slow-release envelope and advances the ring rotation. Stops once everything has settled.
 */
@Composable
fun rememberVisualizerState(playing: Boolean, enabled: Boolean): VisualizerState {
    val analyzer = LocalContext.current.container.spectrum
    val state = remember { VisualizerState() }
    if (enabled) {
        DisposableEffect(Unit) {
            analyzer.watch()
            onDispose { analyzer.unwatch() }
        }
    }
    LaunchedEffect(playing, enabled) {
        val target = FloatArray(SpectrumAnalyzer.BANDS)
        var last = 0L
        while (true) {
            var settled = false
            withFrameNanos { now ->
                val dt = if (last == 0L) 1 / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val live = enabled && playing && analyzer.read(target)
                val decay = 0.86f.pow(dt * 60f)
                var energy = 0f
                var peak = 0f
                for (i in state.bands.indices) {
                    val t = if (live) target[i] else 0f
                    val v = if (t > state.bands[i]) state.bands[i] + (t - state.bands[i]) * 0.65f else state.bands[i] * decay
                    state.bands[i] = v
                    if (i < 8) energy += v
                    if (v > peak) peak = v
                }
                state.energy = energy / 8f
                if (playing) state.rotation = (state.rotation + dt * 9f) % 360f
                state.frame = now
                settled = !playing && peak < 0.005f
            }
            if (settled) break
        }
    }
    return state
}

/**
 * The Now Playing centerpiece: round cover art inside a rotating ring of ticks, with the live
 * spectrum radiating outward and a glow that breathes with the bass.
 */
@Composable
fun Reactor(coverId: String?, serverId: String?, visualizer: VisualizerState, modifier: Modifier = Modifier) {
    val hud = Hud.colors
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        Box(
            Modifier.size(side).drawBehind {
                visualizer.frame
                drawReactor(visualizer, hud.accent, hud.accent2, hud.line)
            },
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(coverId, Modifier.size(side * 0.6f), serverId = serverId, size = CoverSize.LARGE, shape = CircleShape)
        }
    }
}

private fun DrawScope.drawReactor(v: VisualizerState, accent: Color, accent2: Color, line: Color) {
    val c = center
    val r = size.minDimension / 2
    fun polar(radius: Float, degrees: Float): Offset {
        val a = Math.toRadians(degrees.toDouble())
        return Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
    }

    // Core glow, brighter with the bass.
    drawCircle(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.18f + v.energy * 0.4f), Color.Transparent), center = c, radius = r),
        radius = r,
    )

    // Outer tick ring, slowly turning.
    rotate(v.rotation, c) {
        for (i in 0 until 72) {
            val major = i % 6 == 0
            drawLine(
                accent.copy(alpha = if (major) 0.85f else 0.3f),
                polar(r * (if (major) 0.9f else 0.935f), i * 5f), polar(r * 0.975f, i * 5f),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
            )
        }
    }
    drawCircle(line, radius = r * 0.88f, style = Stroke(1.dp.toPx()))

    // Counter-rotating arc segments.
    rotate(-v.rotation * 2.2f, c) {
        val ringR = r * 0.83f
        for (k in 0 until 3) {
            drawArc(
                accent2.copy(alpha = 0.55f), k * 120f, 38f, false,
                topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(ringR * 2, ringR * 2),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }

    // Spectrum: 48 bands mirrored into 96 bars around the cover.
    val bands = v.bands.size
    val bars = bands * 2
    val inner = r * 0.64f
    val maxLen = r * 0.17f
    val glowWidth = 6.dp.toPx()
    val barWidth = 2.4.dp.toPx()
    for (i in 0 until bars) {
        val band = if (i < bands) i else bars - 1 - i
        val level = v.bands[band]
        val angle = -90f + i * 360f / bars
        val start = polar(inner, angle)
        val end = polar(inner + r * 0.015f + level * maxLen, angle)
        val color = lerp(accent, accent2, band / bands.toFloat())
        if (level > 0.05f) drawLine(color.copy(alpha = 0.22f), start, end, glowWidth, StrokeCap.Round)
        drawLine(color.copy(alpha = 0.55f + level * 0.45f), start, end, barWidth, StrokeCap.Round)
    }

    // Rim around the cover.
    drawCircle(accent.copy(alpha = 0.25f), radius = r * 0.605f, style = Stroke(6.dp.toPx()))
    drawCircle(accent, radius = r * 0.605f, style = Stroke(1.5.dp.toPx()))
}

/** Compact spectrum for the mini player. */
@Composable
fun MiniSpectrum(visualizer: VisualizerState, modifier: Modifier = Modifier) {
    val hud = Hud.colors
    Canvas(modifier.size(width = 34.dp, height = 22.dp)) {
        visualizer.frame
        val groups = 7
        val per = visualizer.bands.size / groups
        val w = size.width / (groups * 2 - 1)
        for (g in 0 until groups) {
            var sum = 0f
            for (k in 0 until per) sum += visualizer.bands[g * per + k]
            val level = (sum / per).coerceIn(0.06f, 1f)
            val h = size.height * level
            drawRect(lerp(hud.accent, hud.accent2, g / groups.toFloat()), Offset(g * 2 * w, size.height - h), Size(w, h))
        }
    }
}

/** Big round play/pause with a halo that pulses to the beat. */
@Composable
fun ReactorPlayButton(
    playing: Boolean,
    buffering: Boolean,
    visualizer: VisualizerState,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
) {
    val hud = Hud.colors
    Box(
        modifier
            .size(size)
            .drawBehind {
                visualizer.frame
                val r = this.size.minDimension / 2
                drawCircle(
                    Brush.radialGradient(listOf(hud.accent.copy(alpha = 0.35f + visualizer.energy * 0.5f), Color.Transparent), radius = r * 1.5f),
                    radius = r * 1.5f,
                )
                drawCircle(Brush.linearGradient(listOf(hud.accent, lerp(hud.accent, hud.accent2, 0.45f))), radius = r)
                drawCircle(Color.White.copy(alpha = 0.35f), radius = r * 0.86f, style = Stroke(1.dp.toPx()))
            }
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, indication = ripple(color = Color.Black), interactionSource = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering && playing) {
            CircularProgressIndicator(Modifier.size(size * 0.4f), color = Color(0xFF00080C), strokeWidth = 3.dp)
        } else {
            Icon(icon, contentDescription, Modifier.size(size * 0.5f), tint = Color(0xFF00080C))
        }
    }
}

/** Seek bar drawn as a HUD gauge: ticks, a glowing progress line and a diamond marker. */
@Composable
fun HudSeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val hud = Hud.colors
    var drag by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = drag ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics {
                    contentDescription = "Seek"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { value ->
                        onSeek((value * duration).toLong())
                        true
                    }
                }
                .pointerInput(duration) {
                    detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f).times(duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag?.let { onSeek((it * duration).toLong()) }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                }
                .drawBehind {
                    val y = size.height / 2
                    val x = size.width * fraction
                    for (i in 0..40) {
                        val tx = size.width * i / 40f
                        val major = i % 10 == 0
                        val h = if (major) 7.dp.toPx() else 3.dp.toPx()
                        drawLine(if (tx <= x) hud.accent.copy(alpha = 0.6f) else hud.line, Offset(tx, y + 6.dp.toPx()), Offset(tx, y + 6.dp.toPx() + h), 1.dp.toPx())
                    }
                    drawLine(hud.line, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                    drawLine(hud.accent.copy(alpha = 0.25f), Offset(0f, y), Offset(x, y), 8.dp.toPx(), StrokeCap.Round)
                    drawLine(
                        Brush.horizontalGradient(listOf(hud.accent2, hud.accent), endX = x.coerceAtLeast(1f)),
                        Offset(0f, y), Offset(x, y), 2.5.dp.toPx(), StrokeCap.Round,
                    )
                    val d = (if (drag != null) 9.dp else 7.dp).toPx()
                    val diamond = Path().apply {
                        moveTo(x, y - d); lineTo(x + d, y); lineTo(x, y + d); lineTo(x - d, y); close()
                    }
                    drawCircle(hud.accent.copy(alpha = 0.3f), radius = d * 1.8f, center = Offset(x, y))
                    drawPath(diamond, hud.accent)
                    drawPath(diamond, Color.White.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
                },
        )
        Row {
            Text(
                formatMs(if (drag != null) (fraction * duration).toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium, color = hud.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelMedium, color = hud.dim)
        }
    }
}

/** Icon button for the secondary transport controls, with an "on" pip underneath. */
@Composable
fun HudToggleIcon(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hud = Hud.colors
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .size(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (active) hud.accent else hud.dim)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.size(width = 14.dp, height = 2.dp)
                .then(if (active) Modifier.glowBorder(hud.accent, androidx.compose.ui.graphics.RectangleShape, glow = 4.dp) else Modifier)
                .background(if (active) hud.accent else Color.Transparent),
        )
    }
}
