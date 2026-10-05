package io.github.deadeyebarb.tonearm.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudTopBar
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.isHiRes
import io.github.deadeyebarb.tonearm.ui.common.qualityLabel

/** Scaffold for pushed screens: back arrow, a title that appears as the content scrolls, and actions. */
@Composable
fun DetailScaffold(
    title: String,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val app = LocalActions.current
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            HudTopBar(
                title = title,
                navigationIcon = { IconButton(onClick = { app.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { actions() },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) { content(Modifier) }
    }
}

/** e.g. "FLAC · 24-bit / 96 kHz" when every track shares a format, otherwise the formats present. */
fun summarizeQuality(songs: List<Song>): String? {
    if (songs.isEmpty()) return null
    val suffixes = songs.mapNotNull { it.suffix?.uppercase() }.distinct()
    if (suffixes.size != 1) return suffixes.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    val depth = songs.mapNotNull { it.bitDepth }.maxOrNull()
    val rate = songs.mapNotNull { it.samplingRate }.maxOrNull()
    return qualityLabel(suffixes[0], depth, rate, null).takeIf { it.isNotBlank() }
}

fun anyHiRes(songs: List<Song>) = songs.any { isHiRes(it.bitDepth, it.samplingRate) }

/** Technical metadata line: "2024 // ELECTRONIC // 4 TRACKS". */
@Composable
fun MetaLine(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Hud.colors.dim, textAlign = TextAlign.Center)
}
