package io.github.deadeyebarb.tonearm.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deadeyebarb.tonearm.R
import io.github.deadeyebarb.tonearm.data.AccentColor
import io.github.deadeyebarb.tonearm.data.AppSettings

val Orbitron = FontFamily(
    Font(R.font.orbitron, FontWeight.Normal),
    Font(R.font.orbitron, FontWeight.Medium),
    Font(R.font.orbitron, FontWeight.SemiBold),
    Font(R.font.orbitron, FontWeight.Bold),
    Font(R.font.orbitron, FontWeight.ExtraBold),
    Font(R.font.orbitron, FontWeight.Black),
)

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_regular, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

val TechMono = FontFamily(Font(R.font.share_tech_mono))

/** The HUD palette: a near-black void, glassy panels, and two neon accents. */
@Immutable
data class HudPalette(
    val accent: Color,
    val accent2: Color,
    val void: Color,
    val deep: Color,
    val panel: Color,
    val panelHigh: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
    val danger: Color = Color(0xFFFF4D6D),
    val ok: Color = Color(0xFF3DFFA2),
)

val LocalHud = staticCompositionLocalOf {
    HudPalette(
        accent = Color(AccentColor.CYAN.primary), accent2 = Color(AccentColor.CYAN.secondary),
        void = Color(0xFF04060B), deep = Color(0xFF070B14), panel = Color(0xFF0B1220), panelHigh = Color(0xFF111B2E),
        line = Color(0xFF1C2A44), text = Color(0xFFE6F1FF), dim = Color(0xFF8399B8),
    )
}

object Hud {
    val colors: HudPalette
        @Composable get() = LocalHud.current
}

/** Turns any color into a vivid neon of the same hue; falls back to cyan for grey artwork. */
fun neonize(color: Color): Pair<Color, Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] < 0.15f) return Color(AccentColor.CYAN.primary) to Color(AccentColor.CYAN.secondary)
    val primary = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.72f), 1f)))
    val secondary = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 150f) % 360f, 0.8f, 1f)))
    return primary to secondary
}

private val HudTypography = Typography(
    displayLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = 1.sp),
    displayMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = 1.sp),
    displaySmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 1.sp),
    headlineLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.5.sp),
    headlineSmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 1.5.sp),
    titleMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp),
    titleSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = TechMono, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = TechMono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

/** Chamfered corners everywhere: opposite corners of a panel are cut, like a HUD frame. */
private val HudShapes = Shapes(
    extraSmall = CutCornerShape(3.dp),
    small = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
    medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    large = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
    extraLarge = CutCornerShape(topStart = 22.dp, bottomEnd = 22.dp),
)

@Composable
fun TonearmTheme(settings: AppSettings, artworkColor: Color? = null, content: @Composable () -> Unit) {
    val (wantedAccent, wantedAccent2) = when {
        settings.accent == AccentColor.ARTWORK && artworkColor != null -> neonize(artworkColor)
        else -> Color(settings.accent.primary) to Color(settings.accent.secondary)
    }
    val accent by animateColorAsState(wantedAccent, tween(800), label = "accent")
    val accent2 by animateColorAsState(wantedAccent2, tween(800), label = "accent2")

    val void = if (settings.pureBlack) Color.Black else Color(0xFF04060B)
    val deep = if (settings.pureBlack) Color(0xFF020306) else Color(0xFF070B14)
    // Panels carry a hint of the accent so the whole UI shifts with it.
    val panel = lerp(Color(0xFF0B1220), accent, 0.035f)
    val panelHigh = lerp(Color(0xFF111B2E), accent, 0.05f)
    val line = lerp(Color(0xFF1C2A44), accent, 0.12f)
    val palette = HudPalette(
        accent = accent, accent2 = accent2, void = void, deep = deep, panel = panel, panelHigh = panelHigh,
        line = line, text = Color(0xFFE6F1FF), dim = Color(0xFF8399B8),
    )

    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF00080C),
        primaryContainer = accent.copy(alpha = 0.22f).compositeOver(panel),
        onPrimaryContainer = lerp(accent, Color.White, 0.55f),
        inversePrimary = lerp(accent, Color.Black, 0.4f),
        secondary = accent2,
        onSecondary = Color(0xFF0A0010),
        secondaryContainer = accent2.copy(alpha = 0.2f).compositeOver(panel),
        onSecondaryContainer = lerp(accent2, Color.White, 0.55f),
        tertiary = accent2,
        onTertiary = Color(0xFF0A0010),
        tertiaryContainer = accent2.copy(alpha = 0.2f).compositeOver(panel),
        onTertiaryContainer = lerp(accent2, Color.White, 0.55f),
        background = void,
        onBackground = palette.text,
        surface = deep,
        onSurface = palette.text,
        surfaceVariant = panelHigh,
        onSurfaceVariant = palette.dim,
        surfaceTint = accent,
        inverseSurface = palette.text,
        inverseOnSurface = void,
        error = palette.danger,
        onError = Color.Black,
        errorContainer = Color(0xFF3A0A16),
        onErrorContainer = Color(0xFFFFB3C1),
        outline = lerp(Color(0xFF2C4266), accent, 0.2f),
        outlineVariant = line,
        scrim = Color.Black,
        surfaceBright = Color(0xFF1B2A44),
        surfaceDim = void,
        surfaceContainerLowest = void,
        surfaceContainerLow = deep,
        surfaceContainer = panel,
        surfaceContainerHigh = panelHigh,
        surfaceContainerHighest = lerp(Color(0xFF18263F), accent, 0.06f),
    )
    CompositionLocalProvider(LocalHud provides palette) {
        MaterialTheme(colorScheme = scheme, typography = HudTypography, shapes = HudShapes) {
            CompositionLocalProvider(LocalContentColor provides palette.text, content = content)
        }
    }
}
