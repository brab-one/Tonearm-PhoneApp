package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.integrations.NextSong
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.mixes.RadioPolicy
import io.github.deadeyebarb.tonearm.mixes.RadioSeed
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** The radio playing: what it started from, and its title ("Radio · Middle C", "Picks radio"). */
@Serializable
data class SavedRadio(val seed: RadioSeed, val title: String)

/**
 * Radio: the song playing stays, what follows becomes music like it (or the user's picks), and more is added as it plays,
 * without end. The car's Radio button, Now Playing, the shortcuts and the widget start it; replacing the queue, turning
 * shuffle on or Stop ends it. The songs come from the Tonearm server, else from the library ([io.github.deadeyebarb.tonearm.mixes.Mixer]).
 * Main thread.
 */
class RadioQueue(
    private val player: Player,
    private val c: AppContainer,
    private val scope: CoroutineScope,
    /** Songs to leave out of what's added (dislikes the server doesn't have yet). */
    private val leaveOut: (Song) -> Boolean,
    /** The radio started, stopped or got its title. */
    private val onChange: () -> Unit,
) : Player.Listener {
    /** The radio playing; null when none does. */
    var current: SavedRadio? = null
        private set
    private val refills = RadioRefills(System::currentTimeMillis)
    private var job: Job? = null
    private var retryJob: Job? = null

    /**
     * Starts a radio from [seed]. From a song, what follows the song playing is replaced; the picks (or nothing to start
     * from) replace the queue and play. Shuffle goes off. Gives the problem when nothing came, which leaves the queue as it was.
     */
    fun start(seed: RadioSeed, play: Boolean): Deferred<String?> {
        cancelJobs()
        val ticket = refills.restart()
        set(SavedRadio(seed, seed.title))
        if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
        val fromSong = seed.kind == RadioSeed.SONG && player.currentMediaItem != null
        val heard = if (fromSong) queue(upTo = player.currentMediaItemIndex) else emptyList()
        val answer = scope.async {
            val batch = fetch(seed, heard)
            if (!refills.finish(ticket, batch.songs.isNotEmpty())) return@async null
            if (batch.songs.isEmpty()) {
                stop()
                return@async batch.problem ?: "Nothing to play like that"
            }
            set(SavedRadio(seed, batch.title))
            val items = batch.songs.map { c.mediaItems.song(it) }
            if (fromSong && player.currentMediaItem != null) {
                val after = player.currentMediaItemIndex + 1
                if (after < player.mediaItemCount) player.removeMediaItems(after, player.mediaItemCount)
                player.addMediaItems(items)
            } else {
                player.setMediaItems(items)
            }
            if (play || !fromSong) {
                player.prepare()
                player.play()
            }
            null
        }
        job = answer
        return answer
    }

    /** No more songs are added; the queue stays as it is. */
    fun stop() {
        if (current == null) return
        cancelJobs()
        refills.reset()
        current = null
        player.playlistMetadata = MediaMetadata.EMPTY
        onChange()
    }

    /** The radio saved with the queue, once the queue is back in the player. */
    fun restore(saved: SavedRadio?) {
        if (saved == null) return stop()
        cancelJobs()
        refills.reset()
        set(saved)
        refill()
    }

    /** Waits for the next batch: asks for it now unless one is on its way or the last ask failed a moment ago. */
    suspend fun awaitMore() {
        refill(force = true)
        job?.join()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (current == null) return
        // The refill appends in queue order, which shuffle would scatter.
        if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) && player.shuffleModeEnabled) return stop()
        // Everything was taken out of the queue: there's nothing left for the radio to follow.
        if (player.currentTimeline.isEmpty && !refills.asking) return stop()
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) refill()
    }

    private fun set(radio: SavedRadio) {
        val changed = radio != current
        current = radio
        // Android Auto shows it as the queue's title.
        player.playlistMetadata = MediaMetadata.Builder().setTitle(radio.title).build()
        if (changed) onChange()
    }

    /** Adds the next batch when few songs are left ([force]: whatever is left). */
    private fun refill(force: Boolean = false) {
        val radio = current ?: return
        if (player.currentTimeline.isEmpty) return
        val left = if (force) 0 else leftAfter(player.currentTimeline, player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled)
        val ticket = refills.begin(left, repeatOff = force || player.repeatMode == Player.REPEAT_MODE_OFF) ?: return
        val queue = queue(upTo = player.mediaItemCount - 1)
        retryJob?.cancel()
        job = scope.launch {
            val batch = fetch(radio.seed, queue)
            if (!refills.finish(ticket, batch.songs.isNotEmpty())) return@launch
            if (batch.songs.isEmpty()) {
                // Asked again in a while (or at the next song), less often the longer it fails.
                retryJob = scope.launch {
                    delay(refills.retryInMs())
                    refill()
                }
                return@launch
            }
            player.addMediaItems(batch.songs.map { c.mediaItems.song(it) })
        }
    }

    private class Batch(val title: String, val songs: List<QueueSong>, val problem: String?)

    private suspend fun fetch(seed: RadioSeed, queue: List<QueueSong>): Batch {
        val session = c.sessions.active.value ?: runCatching { c.sessions.awaitActive() }.getOrNull()
        val result = try {
            c.mixer.radio(session, seed, queue.map { NextSong(it.song, YouTubeMusic.isYouTube(it.serverId)) }, c.settings.state.value.youtubeFallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Batch(seed.title, emptyList(), e.userMessage())
        }
        val songs = result.songs.filterNot { leaveOut(it.song) }.mapNotNull { next ->
            if (next.youtube) QueueSong(YouTubeMusic.SOURCE_ID, next.song) else session?.let { QueueSong(it.id, next.song) }
        }
        return Batch(result.title, songs, result.problem)
    }

    /** The queue's songs from the first up to [upTo], in queue order. */
    private fun queue(upTo: Int): List<QueueSong> = (0..upTo).mapNotNull { player.getMediaItemAt(it).toQueueSong() }

    private fun cancelJobs() {
        job?.cancel()
        retryJob?.cancel()
    }

    companion object {
        /** A radio from [entry], or the picks radio without one. */
        fun seedOf(entry: QueueSong?): RadioSeed = entry?.let {
            RadioSeed(RadioSeed.SONG, it.song, youtube = YouTubeMusic.isYouTube(it.serverId), local = LocalMusic.isLocal(it.serverId))
        } ?: RadioSeed(RadioSeed.PICKS)

        /** How many songs are left to play after [current], counted up to one more than [RadioPolicy.AHEAD]. */
        fun leftAfter(timeline: Timeline, current: Int, repeatMode: Int, shuffle: Boolean): Int =
            CacheAhead.upcoming(timeline, current, repeatMode, shuffle, RadioPolicy.AHEAD + 1).size
    }
}

/**
 * When the radio asks for its next batch, and which answers still count: one ask at a time, a pause after failed ones
 * ([RadioPolicy.retryDelayMs]), and answers to asks from before the radio restarted or stopped are dropped.
 */
class RadioRefills(private val clock: () -> Long) {
    private var generation = 0
    /** An ask is on its way. */
    var asking = false
        private set
    private var failures = 0
    private var failedAt = 0L

    /** A new radio, or none: earlier asks don't count anymore. */
    fun reset() {
        generation++
        asking = false
        failures = 0
        failedAt = 0
    }

    /** [reset], and the ticket of the new radio's first ask. */
    fun restart(): Int {
        reset()
        asking = true
        return generation
    }

    /** The ticket for an ask when a batch is due with [left] songs to go; null when it isn't. */
    fun begin(left: Int, repeatOff: Boolean): Int? {
        if (!RadioPolicy.wantsMore(left, repeatOff, asking, failures, clock() - failedAt)) return null
        asking = true
        return generation
    }

    /** Whether the answer to [ticket] still counts; [ok] says whether it brought songs. */
    fun finish(ticket: Int, ok: Boolean): Boolean {
        if (ticket != generation) return false
        asking = false
        if (ok) {
            failures = 0
        } else {
            failures++
            failedAt = clock()
        }
        return true
    }

    /** How long until a failed ask may be tried again. */
    fun retryInMs(): Long = (RadioPolicy.retryDelayMs(failures) - (clock() - failedAt)).coerceAtLeast(0)
}
