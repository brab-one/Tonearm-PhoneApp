package io.github.deadeyebarb.tonearm.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.deadeyebarb.tonearm.connect.PhoneConnect
import io.github.deadeyebarb.tonearm.connect.Played
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.serverId
import io.github.deadeyebarb.tonearm.media.songId
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApiException
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reports "now playing" when a song starts and submits a scrobble once it has actually been
 * listened to for half its length or four minutes, whichever comes first (Last.fm rules). Every song,
 * YouTube Music ones and skipped ones too, also goes to the Tonearm server's listening history.
 * What can't be sent (offline) is kept in [file] and sent after the next song, on [retry] or at the next start.
 *
 * One per process, so it outlives the playback service and the last song still gets sent once that
 * stops. [scope] runs on the main thread.
 */
class Scrobbler(
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val connect: PhoneConnect,
    private val scope: CoroutineScope,
    private val file: File,
    private val json: Json,
) : Player.Listener {
    private data class Play(
        val serverId: String,
        val songId: String,
        val startedAt: Long,
        val durationMs: Long,
        val title: String,
        val artist: String?,
        val album: String?,
    )

    @Serializable
    private data class Pending(val serverId: String, val songId: String, val time: Long)

    @Serializable
    private data class Backlog(val scrobbles: List<Pending> = emptyList(), val history: List<Played> = emptyList())

    private var current: Play? = null
    private var listenedMs = 0L
    private var resumedAt = -1L
    private var announced = false
    private val pending = ArrayDeque<Pending>()
    private val history = ArrayDeque<Played>()
    /** One sender at a time; it also keeps the first write to [file] after its first read. */
    private val sending = Mutex()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sending.withLock {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { json.decodeFromString(Backlog.serializer(), file.readText()) }.getOrNull()
                }
                if (saved != null) {
                    pending.addAll(0, saved.scrobbles)
                    history.addAll(0, saved.history)
                }
            }
            send()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        finish()
        begin(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) {
            resumedAt = now
            if (!announced) announce()
        } else if (resumedAt >= 0) {
            listenedMs += now - resumedAt
            resumedAt = -1
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        // A gapless or repeat-one change keeps the player playing, so onIsPlayingChanged doesn't fire
        // for the new song: its clock starts here, once the batch's other callbacks have run.
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && player.isPlaying && resumedAt < 0) {
            resumedAt = SystemClock.elapsedRealtime()
            if (!announced) announce()
        }
    }

    fun begin(item: MediaItem?) {
        val serverId = item?.serverId
        val songId = item?.songId
        current = if (serverId != null && songId != null) {
            val m = item.mediaMetadata
            Play(
                serverId, songId, System.currentTimeMillis(), m.durationMs ?: 0,
                m.title?.toString().orEmpty(), m.artist?.toString(), m.albumTitle?.toString(),
            )
        } else {
            null
        }
        listenedMs = 0
        announced = false
        resumedAt = -1
    }

    /** Call when the player is released so the last song isn't lost. */
    fun finish() {
        val play = current ?: return
        if (resumedAt >= 0) {
            listenedMs += SystemClock.elapsedRealtime() - resumedAt
            resumedAt = SystemClock.elapsedRealtime()
        }
        val threshold = if (play.durationMs > 0) minOf(play.durationMs / 2, 240_000L) else 240_000L
        if (listenedMs >= threshold && listenedMs >= 10_000 && settings.state.value.scrobble) {
            pending.addLast(Pending(play.serverId, play.songId, play.startedAt))
            while (pending.size > 200) pending.removeFirst()
        }
        record(play, listenedMs)
        current = null
        scope.launch { send() }
    }

    /** Sends what's waiting, say once the phone is back online. */
    fun retry() {
        scope.launch { send() }
    }

    private fun announce() {
        val play = current ?: return
        announced = true
        if (!settings.state.value.scrobble) return
        scope.launch {
            runCatching {
                val session = sessions.session(play.serverId) ?: return@launch
                api.scrobble(session, play.songId, System.currentTimeMillis(), submission = false)
            }
        }
    }

    /** Into the Tonearm server's history, in batches. */
    private fun record(play: Play, listenedMs: Long) {
        if (!settings.state.value.scrobble || play.artist.isNullOrBlank() || play.title.isBlank() || listenedMs < 1_000) return
        val source = when {
            YouTubeMusic.isYouTube(play.serverId) -> "youtube"
            LocalMusic.isLocal(play.serverId) -> "local"
            else -> "library"
        }
        history.addLast(Played(play.startedAt, play.artist, play.title, play.album, play.durationMs, listenedMs, source))
        while (history.size > 500) history.removeFirst()
    }

    /** The scrobbles in order, then the history in one batch; what's left stays in [file]. */
    private suspend fun send() = sending.withLock {
        if (pending.isEmpty() && history.isEmpty()) return@withLock
        save()
        try {
            sendScrobbles()
            sendHistory()
        } finally {
            save()
        }
    }

    private suspend fun sendScrobbles() {
        while (pending.isNotEmpty()) {
            val next = pending.first()
            try {
                val session = sessions.session(next.serverId)
                if (session != null) api.scrobble(session, next.songId, next.time, submission = true)
            } catch (_: SubsonicApiException) {
                // The server turned it down; sending it again won't change that.
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return // Offline, or the server isn't answering properly: later.
            }
            pending.removeFirstOrNull()
        }
    }

    private suspend fun sendHistory() {
        val batch = history.toList().ifEmpty { return }
        try {
            // Without a Tonearm server that keeps a history there's nowhere to send them.
            connect.played(batch)
            history.removeAll(batch.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun save() {
        val backlog = Backlog(pending.toList(), history.toList())
        withContext(Dispatchers.IO) {
            runCatching {
                if (backlog.scrobbles.isEmpty() && backlog.history.isEmpty()) {
                    file.delete()
                } else {
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(json.encodeToString(Backlog.serializer(), backlog))
                    tmp.renameTo(file)
                }
            }
        }
    }
}
