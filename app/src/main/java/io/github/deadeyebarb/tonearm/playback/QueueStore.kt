package io.github.deadeyebarb.tonearm.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.media.MediaItemFactory
import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedItem(val serverId: String, val song: Song, val context: String? = null)

@Serializable
data class SavedQueue(
    val items: List<SavedItem> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/** Persists the play queue so it survives process death and powers playback resumption. */
class QueueStore(context: Context, private val json: Json, private val factory: MediaItemFactory) {
    private val file = File(context.filesDir, "queue.json")

    /** Main thread: reads the player. */
    fun snapshot(player: Player): SavedQueue {
        val items = (0 until player.mediaItemCount).mapNotNull { i ->
            val item = player.getMediaItemAt(i)
            item.toQueueSong()?.let { SavedItem(it.serverId, it.song, MediaIds.parse(item.mediaId).context) }
        }
        return SavedQueue(
            items = items,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    suspend fun save(queue: SavedQueue) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "queue.json.tmp")
        tmp.writeText(json.encodeToString(SavedQueue.serializer(), queue))
        tmp.renameTo(file)
    }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(SavedQueue.serializer(), file.readText()) }.getOrNull()?.takeIf { it.items.isNotEmpty() }
    }

    fun mediaItems(queue: SavedQueue): List<MediaItem> = queue.items.map { factory.song(it.serverId, it.song, it.context) }
}
