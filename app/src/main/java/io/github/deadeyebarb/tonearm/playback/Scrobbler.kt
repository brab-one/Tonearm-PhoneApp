package io.github.deadeyebarb.tonearm.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.integrations.IntegrationsService
import io.github.deadeyebarb.tonearm.media.serverId
import io.github.deadeyebarb.tonearm.media.songId
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Reports "now playing" when a song starts and submits a scrobble once it has actually been
 * listened to for half its length or four minutes, whichever comes first (Last.fm rules).
 * Submissions that fail while offline are retried after the next successful one.
 */
class Scrobbler(
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val integrations: IntegrationsService,
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
    private data class MalojaPending(val play: Play, val listenedMs: Long)

    private var current: Play? = null
    private var listenedMs = 0L
    private var resumedAt = -1L
    private var announced = false
    private val pending = ArrayDeque<Pending>()
    private val malojaPending = ArrayDeque<MalojaPending>()

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
        if (listenedMs >= threshold && listenedMs >= 10_000) {
            submit(Pending(play.serverId, play.songId, play.startedAt))
            submitToMaloja(MalojaPending(play, listenedMs))
        }
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

    /** Direct Maloja scrobbles, when enabled (otherwise the music server may forward them itself). */
    private fun submitToMaloja(entry: MalojaPending) {
        val config = integrations.state.value.maloja?.takeIf { it.scrobble } ?: return
        if (entry.play.artist.isNullOrBlank() || entry.play.title.isBlank()) return
        scope.launch {
            malojaPending.addLast(entry)
            while (malojaPending.size > 200) malojaPending.removeFirst()
            val key = integrations.malojaKey(config)
            while (malojaPending.isNotEmpty()) {
                val next = malojaPending.first()
                try {
                    integrations.maloja.scrobble(
                        config, key,
                        artists = splitArtists(next.play.artist.orEmpty()),
                        title = next.play.title,
                        album = next.play.album,
                        listenedSeconds = (next.listenedMs / 1000).toInt(),
                        lengthSeconds = (next.play.durationMs / 1000).toInt().takeIf { it > 0 },
                        timeSeconds = next.play.startedAt / 1000,
                    )
                    malojaPending.removeFirst()
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    /** "A feat. B" / "A, B" → [A, B]; Maloja also parses this server-side, this just helps. */
    private fun splitArtists(artist: String): List<String> =
        artist.split(Regex("""\s*(?:,|;| feat\.? | ft\.? | & )\s*""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(artist) }

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
