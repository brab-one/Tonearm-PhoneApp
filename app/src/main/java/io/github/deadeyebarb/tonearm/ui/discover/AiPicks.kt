package io.github.deadeyebarb.tonearm.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.connect.AiPick
import io.github.deadeyebarb.tonearm.connect.AiPicks
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.ui.YtAlbumRoute
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Albums the Tonearm server's AI (Ollama) suggests from what you play and like, by artists you don't have.
 * Tap one to open it on YouTube Music; Request asks Lidarr for it. Only there when the server has Ollama.
 */
@Composable
fun AiPicksPanel(actions: AppActions, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    if (server?.recommendations != true) return
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    var picks by remember { mutableStateOf<AiPicks?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var asks by remember { mutableIntStateOf(0) }
    LaunchedEffect(server?.baseUrl, asks) {
        var refresh = asks > 0
        while (true) {
            try {
                picks = c.connect.aiPicks(refresh)
                failed = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.userMessage()
                break
            }
            refresh = false
            if (picks?.running != true) break
            delay(10_000)
        }
    }
    val hud = Hud.colors
    val current = picks
    HudPanel(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI PICKS", style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp))
                    Text(
                        when {
                            current == null -> "ASKING THE TONEARM SERVER…"
                            current.running -> "LISTENING TO WHAT YOU PLAY… A FEW MINUTES"
                            current.picks.isEmpty() -> "NOTHING YET"
                            else -> "${current.picks.size} ALBUMS FOR YOU // " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(current.madeAt))
                        },
                        style = MaterialTheme.typography.labelSmall, color = hud.accent,
                    )
                }
                if (current != null && !current.running) HudButton("Ask again", { asks++ }, filled = false)
            }
            (failed ?: current?.problem)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = hud.danger, modifier = Modifier.padding(top = 6.dp))
            }
            current?.picks?.forEach { pick ->
                AiPickRow(pick, canRequest = integrations.lidarr != null, onYouTube = settings.youtubeFallback, actions = actions)
            }
        }
    }
}

@Composable
private fun AiPickRow(pick: AiPick, canRequest: Boolean, onYouTube: Boolean, actions: AppActions) {
    val c = LocalContext.current.container
    val hud = Hud.colors
    val open = Modifier.clickable(enabled = onYouTube) {
        actions.launch {
            val album = c.catalog.findAlbum(pick.artist, pick.album)
            if (album == null) actions.message("${pick.album} isn't on YouTube Music") else actions.navigate(YtAlbumRoute(album.url, album.title, album.artist, album.imageUrl))
        }
    }
    Row(open.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pick.album, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (pick.artist + (pick.year?.let { " · $it" } ?: "")).uppercase(),
                style = MaterialTheme.typography.labelSmall, color = hud.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (pick.why.isNotBlank()) {
                Text(pick.why, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (canRequest) {
            Spacer(Modifier.width(10.dp))
            HudButton("Request", { actions.requestAlbum(pick.album, pick.artist) }, filled = false)
        }
    }
}
