package io.github.deadeyebarb.tonearm.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic

/** The only cover sizes the UI asks for, so a cached image is reused everywhere (and offline). */
object CoverSize {
    const val THUMB = 160
    const val CARD = 400
    const val LARGE = 1000
    val ALL = listOf(THUMB, CARD, LARGE)
}

fun coverCacheKey(serverId: String, coverId: String, size: Int) = "cover:$serverId:$coverId:$size"

/** Cover art request with a stable cache key (the URL itself changes: every request gets a new auth salt). */
fun coverRequest(context: android.content.Context, session: ServerSession, coverId: String, size: Int): ImageRequest {
    val key = coverCacheKey(session.id, coverId, size)
    return ImageRequest.Builder(context)
        .data(session.coverUrl(coverId, size).toString())
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .build()
}

@Composable
fun CoverArt(
    coverId: String?,
    modifier: Modifier = Modifier,
    serverId: String? = null,
    size: Int = CoverSize.CARD,
    shape: Shape = MaterialTheme.shapes.small,
    placeholder: ImageVector = Icons.Rounded.Album,
) {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val session = remember(serverId, active) {
        if (serverId == null || serverId == active?.id) active else c.sessions.cached(serverId)
    }
    val request: Any? = remember(session, coverId, size, serverId) {
        when {
            coverId == null -> null
            // YouTube Music covers are URLs already.
            YouTubeMusic.isYouTube(serverId) -> coverId
            session == null -> null
            else -> coverRequest(context, session, coverId, size)
        }
    }
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, Modifier.fillMaxSize(0.42f), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
        if (request != null) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
