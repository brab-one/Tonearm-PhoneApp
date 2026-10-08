package io.github.deadeyebarb.tonearm.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.deadeyebarb.tonearm.media.MediaItemFactory
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/** What the decoder is actually being fed (may differ from the library file when transcoding). */
data class AudioFormatInfo(
    val codec: String,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val bitrate: Int?,
)

data class PlayerUiState(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val currentIndex: Int = C.INDEX_UNSET,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    /** Position at the last player event; poll [PlayerConnection.position] for a live value. */
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Queue in playlist order. */
    val queue: List<MediaItem> = emptyList(),
    /** Indices into [queue] in the order they will play (differs when shuffling). */
    val order: List<Int> = emptyList(),
    val error: String? = null,
    val format: AudioFormatInfo? = null,
)

/** The UI's handle on [PlaybackService], via a MediaController. Main thread only. */
class PlayerConnection(private val context: Context, private val factory: MediaItemFactory) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    val position: Long get() = controller?.currentPosition ?: _state.value.positionMs
    val bufferedPosition: Long get() = controller?.bufferedPosition ?: 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            update(
                player,
                rebuildQueue = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED),
            )
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null || future !== f) {
                if (future === f) future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            update(c, rebuildQueue = true)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        pending.clear()
        _state.update { it.copy(connected = false) }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run {
            pending += block
            connect()
        }
    }

    private fun update(p: Player, rebuildQueue: Boolean) {
        val previous = _state.value
        val queue = if (rebuildQueue || !previous.connected) (0 until p.mediaItemCount).map(p::getMediaItemAt) else previous.queue
        _state.value = PlayerUiState(
            connected = true,
            current = p.currentMediaItem,
            currentIndex = p.currentMediaItemIndex,
            isPlaying = p.isPlaying,
            playWhenReady = p.playWhenReady,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: p.currentMediaItem?.mediaMetadata?.durationMs ?: 0,
            positionMs = p.currentPosition,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            queue = queue,
            order = if (rebuildQueue || !previous.connected) playbackOrder(p) else previous.order,
            error = p.playerError?.let { e -> (e.cause ?: e).userMessage() },
            format = formatOf(p),
        )
    }

    private fun playbackOrder(p: Player): List<Int> {
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val order = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return order
    }

    private fun formatOf(p: Player): AudioFormatInfo? {
        val group = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
        val format = (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) ?: return null
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV -> "PCM"
            else -> format.sampleMimeType?.substringAfter('/')?.uppercase() ?: return null
        }
        val bitDepth = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
        return AudioFormatInfo(
            codec = codec,
            sampleRate = format.sampleRate.takeIf { it > 0 },
            bitDepth = bitDepth,
            channels = format.channelCount.takeIf { it > 0 },
            bitrate = format.bitrate.takeIf { it > 0 } ?: format.averageBitrate.takeIf { it > 0 },
        )
    }

    fun play(items: List<QueueSong>, startIndex: Int = 0, shuffle: Boolean = false, context: String? = null, startPositionMs: Long = 0) {
        if (items.isEmpty()) return
        withController { c ->
            val media = items.map { factory.song(it, context) }
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) Random.nextInt(media.size) else startIndex.coerceIn(media.indices)
            c.setMediaItems(media, start, startPositionMs)
            c.prepare()
            c.play()
        }
    }

    fun playNext(items: List<QueueSong>) {
        if (items.isEmpty()) return
        withController { c ->
            val media = items.map { factory.song(it) }
            if (c.mediaItemCount == 0) {
                c.setMediaItems(media)
                c.prepare()
                c.play()
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, media)
            }
        }
    }

    fun enqueue(items: List<QueueSong>) {
        if (items.isEmpty()) return
        withController { c ->
            val media = items.map { factory.song(it) }
            if (c.mediaItemCount == 0) {
                c.setMediaItems(media)
                c.prepare()
            } else {
                c.addMediaItems(media)
            }
        }
    }

    fun retry() = withController { it.prepare() }

    /** Voice search ("play X on Tonearm"); the service resolves the query, an empty one shuffles everything. */
    fun playFromSearch(query: String) = withController { c ->
        c.setMediaItem(
            MediaItem.Builder()
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
                .build(),
        )
        c.prepare()
        c.play()
    }
    fun togglePlayPause() = withController { Util.handlePlayPauseButtonAction(it) }
    fun pause() = withController { it.pause() }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun remove(index: Int) = withController { it.removeMediaItem(index) }

    /** Takes every song [match] picks out of the queue; when the one playing goes, the next one plays. */
    fun removeWhere(match: (MediaItem) -> Boolean) = withController { c ->
        for (i in c.mediaItemCount - 1 downTo 0) if (match(c.getMediaItemAt(i))) c.removeMediaItem(i)
    }

    fun skipTo(index: Int) = withController {
        it.seekToDefaultPosition(index)
        Util.handlePlayButtonAction(it)
    }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Removes everything that would play after the current song. */
    fun clearUpcoming() = withController { c ->
        val current = c.currentMediaItemIndex
        if (c.shuffleModeEnabled) {
            val keep = c.currentMediaItem ?: return@withController
            c.setMediaItems(listOf(keep), 0, c.currentPosition)
        } else if (current + 1 < c.mediaItemCount) {
            c.removeMediaItems(current + 1, c.mediaItemCount)
        }
    }
}
