package io.github.deadeyebarb.tonearm.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.Likes.Companion.toTrackRef
import io.github.deadeyebarb.tonearm.integrations.Fetch
import io.github.deadeyebarb.tonearm.integrations.FetchState
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic

/** Where a YouTube Music song stands: in your library, downloading in Lidarr, or requested there. */
@Composable
fun rememberFetchState(song: Song, serverId: String?): FetchState? {
    if (!YouTubeMusic.isYouTube(serverId)) return null
    val c = LocalContext.current.container
    val activity by c.fetches.activity.collectAsStateWithLifecycle()
    val pending by c.likes.pending.items.collectAsStateWithLifecycle()
    val like = pending.firstOrNull { it.ref.youtubeId == song.id }
    val state by produceState<FetchState?>(null, song.id, activity, like) {
        value = c.fetches.stateOf(like?.ref ?: song.toTrackRef(), like?.requestedAlbum, like?.requestedArtist, c.sessions.active.value)
            ?: if (like?.request != null && like.requestedAlbum != null) FetchState(Fetch.REQUESTED) else null
    }
    return state
}

@Composable
fun FetchIcon(state: FetchState?, modifier: Modifier = Modifier) {
    val hud = Hud.colors
    when (state?.fetch) {
        Fetch.IN_LIBRARY -> Icon(Icons.Rounded.CloudDone, "In your library", modifier.size(14.dp), tint = hud.ok)
        Fetch.DOWNLOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Downloading, "Downloading in Lidarr", modifier.size(14.dp), tint = hud.accent)
            state.progress?.let { Text(" ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = hud.accent) }
        }
        Fetch.REQUESTED -> Icon(Icons.Rounded.Schedule, "Requested in Lidarr", modifier.size(14.dp), tint = hud.accent2)
        null -> Unit
    }
}

fun FetchState.label(): String = when (fetch) {
    Fetch.IN_LIBRARY -> "IN YOUR LIBRARY"
    Fetch.DOWNLOADING -> "DOWNLOADING" + (progress?.let { " ${(it * 100).toInt()}%" } ?: "")
    Fetch.REQUESTED -> "REQUESTED"
}
