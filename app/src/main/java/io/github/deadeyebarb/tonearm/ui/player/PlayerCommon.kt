package io.github.deadeyebarb.tonearm.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.playback.PlayerUiState
import io.github.deadeyebarb.tonearm.ui.common.CoverSize
import io.github.deadeyebarb.tonearm.ui.common.coverCacheKey
import io.github.deadeyebarb.tonearm.ui.common.coverRequest
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Live playback position, polled while playing. */
@Composable
fun rememberPosition(state: PlayerUiState): State<Long> {
    val player = LocalContext.current.container.player
    return produceState(state.positionMs, state.positionMs, state.isPlaying, state.current?.mediaId) {
        value = player.position
        while (state.isPlaying) {
            delay(250)
            value = player.position
        }
    }
}

/** A vivid color from the cover art, used to tint the Now Playing background. */
@Composable
fun rememberArtworkColor(serverId: String?, coverId: String?): Color? {
    val context = LocalContext.current
    val c = context.container
    var color by remember(serverId, coverId) { mutableStateOf<Color?>(null) }
    LaunchedEffect(serverId, coverId) {
        if (serverId == null || coverId == null) return@LaunchedEffect
        // The same covers CoverArt shows: YouTube Music's are URLs, the phone's come from its media library.
        val request = when {
            YouTubeMusic.isYouTube(serverId) -> ImageRequest.Builder(context).data(coverId)
            LocalMusic.isLocal(serverId) -> ImageRequest.Builder(context).data(c.mediaItems.artworkUri(LocalMusic.SOURCE_ID, coverId))
            else -> {
                val session = c.sessions.cached(serverId) ?: return@LaunchedEffect
                coverRequest(context, session, coverId, CoverSize.THUMB).newBuilder()
                    .memoryCacheKey(coverCacheKey(session.id, coverId, CoverSize.THUMB) + ":sw")
            }
        }
        val result = c.imageLoader.execute(request.allowHardware(false).build()) as? SuccessResult ?: return@LaunchedEffect
        color = withContext(Dispatchers.Default) {
            var bitmap = result.image.toBitmap()
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            (palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch)?.rgb?.let(::Color)
        }
    }
    return color
}
