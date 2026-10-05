package io.github.deadeyebarb.tonearm.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.ui.theme.Hud

/** Plays an artist you don't have from YouTube Music; hidden when that's turned off. */
@Composable
fun YouTubePlayButton(artist: String, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val settings by LocalContext.current.container.settings.state.collectAsStateWithLifecycle()
    if (!settings.youtubeFallback) return
    val hud = Hud.colors
    IconButton(onClick = { actions.playFromYouTube(artist) }, modifier = modifier) {
        Box(Modifier.size(36.dp).border(1.5.dp, hud.accent2, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Play $artist from YouTube Music", tint = hud.accent2)
        }
    }
}
