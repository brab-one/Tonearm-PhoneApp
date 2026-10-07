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
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Reports "now playing" when a song starts and submits a scrobble once it has actually been
 * listened to for half its length or four minutes, whichever comes first (Last.fm rules). Every song,
 * YouTube Music ones and skipped ones too, also goes to the Tonearm server's listening history.
 * Submissions that fail while offline are retried after the next successful one.
 */
class Scrobbler(
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val connect: PhoneConnect,
    private val scope: CoroutineScope,
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
    private data class Pending(val serverId: String, val songId: String, val time: Long)

    private var current: Play? = null
    private var listenedMs = 0L
    private var resumedAt = -1L
    private var announced = false
    private val pending = ArrayDeque<Pending>()
    private val history = ArrayDeque<Played>()
    private val historyLock = Mutex()

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
        if (listenedMs >= threshold && listenedMs >= 10_000) submit(Pending(play.serverId, play.songId, play.startedAt))
        record(play, listenedMs)
        current = null
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

    /** Into the Tonearm server's history, in batches; what doesn't get there waits for the next song. */
    private fun record(play: Play, listenedMs: Long) {
        if (!settings.state.value.scrobble || play.artist.isNullOrBlank() || play.title.isBlank() || listenedMs < 1_000) return
        val source = when {
            YouTubeMusic.isYouTube(play.serverId) -> "youtube"
            LocalMusic.isLocal(play.serverId) -> "local"
            else -> "library"
        }
        history.addLast(Played(play.startedAt, play.artist, play.title, play.album, play.durationMs, listenedMs, source))
        while (history.size > 500) history.removeFirst()
        scope.launch {
            historyLock.withLock {
                val batch = history.toList().ifEmpty { return@withLock }
                try {
                    // Without a Tonearm server that keeps a history there's nowhere to send them.
                    connect.played(batch)
                    history.removeAll(batch.toSet())
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun submit(entry: Pending) {
        if (!settings.state.value.scrobble) return
        scope.launch {
            pending.addLast(entry)
            while (pending.size > 200) pending.removeFirst()
            while (pending.isNotEmpty()) {
                val next = pending.first()
                try {
                    val session = sessions.session(next.serverId)
                    if (session != null) api.scrobble(session, next.songId, next.time, submission = true)
                    pending.removeFirst()
                } catch (_: IOException) {
                    break
                }
            }
        }
    }
}
