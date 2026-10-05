package io.github.deadeyebarb.tonearm.playback

import io.github.deadeyebarb.tonearm.data.QueueEnd
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.integrations.Continuation
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic

/** "When the queue ends" in Settings: what to append after the last song (see [Continuation]). */
class QueueContinuation(
    api: SubsonicApi,
    private val sessions: SessionManager,
    youtube: YouTubeMusic,
    private val settings: SettingsRepository,
) {
    private val continuation = Continuation(api, youtube)

    suspend fun next(last: QueueSong, played: Set<String>): List<QueueSong> {
        val s = settings.state.value
        val session = sessions.active.value
        val songs = when (s.whenQueueEnds) {
            QueueEnd.STOP -> emptyList()
            QueueEnd.PLAYLIST -> session?.let { continuation.anotherPlaylist(it, played) }.orEmpty()
            QueueEnd.SIMILAR -> continuation.similar(last.song, YouTubeMusic.isYouTube(last.serverId), session, played, s.youtubeFallback)
        }
        return songs.mapNotNull { next ->
            if (next.youtube) QueueSong(YouTubeMusic.SOURCE_ID, next.song) else session?.let { QueueSong(it.id, next.song) }
        }
    }

    companion object {
        fun key(entry: QueueSong) = Continuation.key(entry.song.id, YouTubeMusic.isYouTube(entry.serverId))
    }
}
