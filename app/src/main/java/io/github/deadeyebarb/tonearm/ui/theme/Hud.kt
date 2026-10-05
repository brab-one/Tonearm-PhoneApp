package io.github.deadeyebarb.tonearm.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app-wide backdrop: a deep gradient, a faint grid and two soft neon glows. */
@Composable
fun HudBackground(modifier: Modifier = Modifier) {
    val hud = Hud.colors
    Box(
        modifier.fillMaxSize().drawWithCache {
            val step = 28.dp.toPx()
            val gridColor = hud.accent.copy(alpha = 0.04f)
            val base = Brush.verticalGradient(listOf(hud.deep, hud.void))
            val topGlow = Brush.radialGradient(
                listOf(hud.accent.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width * 0.1f, 0f), radius = size.width * 0.95f,
            )
            val bottomGlow = Brush.radialGradient(
                listOf(hud.accent2.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(size.width, size.height * 0.85f), radius = size.width,
            )
            onDrawBehind {
                drawRect(base)
                var x = 0f
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
                drawRect(topGlow)
                drawRect(bottomGlow)
            }
        },
    )
}

/** A crisp neon edge with a soft halo around it, drawn over the content. */
fun Modifier.glowBorder(color: Color, shape: Shape, width: Dp = 1.dp, glow: Dp = 6.dp, alpha: Float = 1f): Modifier =
    drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val w = width.toPx()
        val g = glow.toPx()
        onDrawWithContent {
            drawContent()
            for (i in 3 downTo 1) {
                drawPath(path, color.copy(alpha = alpha * 0.07f * (4 - i)), style = Stroke(w + g * i * 2 / 3f))
            }
            drawPath(path, color.copy(alpha = alpha), style = Stroke(w))
        }
    }

/** Targeting-reticle brackets at the four corners, [inset] outside the bounds. */
fun Modifier.cornerBrackets(color: Color, length: Dp = 14.dp, stroke: Dp = 2.dp, inset: Dp = 6.dp): Modifier = drawWithContent {
    drawContent()
    val l = length.toPx()
    val s = stroke.toPx()
    val i = inset.toPx()
    val x0 = -i
    val y0 = -i
    val x1 = size.width + i
    val y1 = size.height + i
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx * l, y), s, cap = StrokeCap.Square)
        drawLine(color, Offset(x, y), Offset(x, y + dy * l), s, cap = StrokeCap.Square)
    }
    corner(x0, y0, 1f, 1f)
    corner(x1, y0, -1f, 1f)
    corner(x0, y1, 1f, -1f)
    corner(x1, y1, -1f, -1f)
}

/** Text style with a neon glow. */
fun TextStyle.glow(color: Color, radius: Float = 18f) = copy(shadow = Shadow(color, Offset.Zero, radius))

/** A glassy panel with a chamfered frame. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    glow: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val hud = Hud.colors
    val framed = if (glow) {
        modifier.glowBorder(hud.accent.copy(alpha = 0.7f), shape, glow = 8.dp)
    } else {
        modifier.border(1.dp, hud.line, shape)
    }
    Box(framed.clip(shape).background(hud.panel.copy(alpha = 0.78f)), content = content)
}

private val ButtonText = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.sp)

/** Chamfered neon button. Filled for the main action, outlined otherwise. */
@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val hud = Hud.colors
    val shape = MaterialTheme.shapes.small
    val color = if (enabled) hud.accent else hud.dim
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (filled) {
        Brush.horizontalGradient(listOf(color, lerp(color, hud.accent2, 0.35f)))
    } else {
        SolidColor(color.copy(alpha = if (pressed) 0.22f else 0.08f))
    }
    val content = if (filled) Color(0xFF00080C) else color
    Row(
        modifier
            .heightIn(min = 46.dp)
            .glowBorder(color, shape, glow = if (filled) 10.dp else 6.dp, alpha = if (enabled) (if (pressed) 1f else 0.85f) else 0.4f)
            .clip(shape)
            .background(fill)
            .clickable(interaction, ripple(color = content), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = ButtonText, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small bracketed label, e.g. HI-RES or OFFLINE. */
@Composable
fun HudTag(text: String, modifier: Modifier = Modifier, color: Color = Hud.colors.accent, filled: Boolean = false) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier
            .clip(shape)
            .background(color.copy(alpha = if (filled) 0.9f else 0.1f))
            .border(1.dp, color.copy(alpha = 0.8f), shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
            color = if (filled) Color.Black else color,
            maxLines = 1,
        )
    }
}

private val SectionText = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 2.sp)

/** "▌ RECENTLY ADDED ───────── ALL ›" */
@Composable
fun HudSectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    val hud = Hud.colors
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 16.dp).glowBorder(hud.accent, RectangleShape, glow = 5.dp).background(hud.accent))
        Spacer(Modifier.width(10.dp))
        Text(title.uppercase(), style = SectionText, color = hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.weight(1f).height(1.dp)
                .background(Brush.horizontalGradient(listOf(hud.accent.copy(alpha = 0.5f), Color.Transparent))),
        )
        if (onSeeAll != null) {
            Text(
                "ALL ›", style = MaterialTheme.typography.labelMedium, color = hud.accent,
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onSeeAll).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Two counter-rotating arcs and a blinking caption. */
@Composable
fun HudLoader(modifier: Modifier = Modifier, label: String = "SYNCING") {
    val hud = Hud.colors
    val transition = rememberInfiniteTransition(label = "loader")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "angle")
    val blink by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blink")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(56.dp)) {
            val s = 3.dp.toPx()
            drawCircle(hud.accent.copy(alpha = 0.15f), radius = size.minDimension / 2, style = Stroke(1.dp.toPx()))
            drawArc(hud.accent, angle, 100f, false, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(
                hud.accent2, 180f - angle * 1.5f, 70f, false,
                topLeft = Offset(s * 3, s * 3), size = Size(size.width - s * 6, size.height - s * 6),
                style = Stroke(s, cap = StrokeCap.Round),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp), color = hud.accent.copy(alpha = blink))
    }
}

/** Transparent top bar with an uppercase Orbitron title. */
@Composable
fun HudTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val hud = Hud.colors
    TopAppBar(
        title = {
            Column {
                Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = hud.void.copy(alpha = 0.92f),
            titleContentColor = hud.text,
            navigationIconContentColor = hud.text,
            actionIconContentColor = hud.text,
        ),
    )
}

/** A softly pulsing status light. */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Canvas(modifier.size(8.dp)) {
        drawCircle(color.copy(alpha = alpha * 0.3f), radius = size.minDimension)
        drawCircle(color.copy(alpha = alpha), radius = size.minDimension / 2.4f)
    }
}

/** Three bouncing bars: "this one is playing". */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Hud.colors.accent) {
    val transition = rememberInfiniteTransition(label = "eq")
    val a by transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "a")
    val b by transition.animateFloat(1f, 0.25f, infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "b")
    val c by transition.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "c")
    Canvas(modifier.size(16.dp)) {
        val w = size.width / 5
        listOf(a, b, c).forEachIndexed { i, level ->
            val h = size.height * (if (playing) level else 0.25f)
            drawRect(color, topLeft = Offset(w * i * 2, size.height - h), size = Size(w, h))
        }
    }
}

/** Thin neon horizontal rule that fades out to the right. */
@Composable
fun HudRule(modifier: Modifier = Modifier) {
    val hud = Hud.colors
    Box(modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(hud.accent.copy(alpha = 0.35f), hud.line, Color.Transparent))))
}
