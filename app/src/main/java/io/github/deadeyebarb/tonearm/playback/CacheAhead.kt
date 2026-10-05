package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.media.MediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Caches the next few songs of the queue (in play order, shuffle included) while the current one
 * plays, so skipping is instant and a patchy connection doesn't stop the music.
 */
class CacheAhead(
    private val player: Player,
    private val media: MediaEngine,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : Player.Listener {
    private var job: Job? = null

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            schedule()
        }
    }

    private fun schedule() {
        job?.cancel()
        val count = settings.state.value.cacheAhead
        if (count <= 0 || !player.isPlaying) return
        val upcoming = upcoming(player.currentTimeline, player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled, count)
            .map { player.getMediaItemAt(it) }
        job = scope.launch {
            // Let the current song's own buffering go first.
            delay(START_DELAY_MS)
            for (item in upcoming) cache(item)
        }
    }

    private suspend fun cache(item: MediaItem) {
        val uri = item.localConfiguration?.uri ?: return
        val writer = media.cacheWriter(uri) ?: return
        val job = coroutineContext[Job]
        val cancel = job?.invokeOnCompletion { writer.cancel() }
        try {
            withContext(Dispatchers.IO) { writer.cache() }
        } catch (_: IOException) {
            // Cancelled, offline or the song is gone: it just won't be cached ahead.
        } finally {
            cancel?.dispose()
        }
        coroutineContext.ensureActive()
    }

    fun release() {
        job?.cancel()
    }

    companion object {
        private const val START_DELAY_MS = 3_000L

        /** Indexes of the next [count] items after [current], in the order the player will play them. */
        fun upcoming(timeline: Timeline, current: Int, repeatMode: Int, shuffle: Boolean, count: Int): List<Int> {
            if (timeline.isEmpty || current == C.INDEX_UNSET) return emptyList()
            // Repeat-one would only ever give the current song; look at the queue order instead.
            val mode = if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeatMode
            val out = mutableListOf<Int>()
            var index = current
            while (out.size < count) {
                index = timeline.getNextWindowIndex(index, mode, shuffle)
                if (index == C.INDEX_UNSET || index == current || index in out) break
                out += index
            }
            return out
        }
    }
}
