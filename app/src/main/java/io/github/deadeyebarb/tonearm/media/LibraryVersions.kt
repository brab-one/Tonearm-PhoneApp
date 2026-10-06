package io.github.deadeyebarb.tonearm.media

import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.likes.findSong
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the library's own file for a song: the FLAC Lidarr downloaded for a YouTube Music song, or
 * the new file the server has after Lidarr upgraded one (MP3 → FLAC, sometimes under a new id).
 */
class LibraryVersions(private val api: SubsonicApi) {
    private val checked = ConcurrentHashMap<String, Long>()

    /**
     * What to play or download instead of [entry], or null to keep it. Results for the same file are
     * reused for a few minutes, so queue changes don't ask the server over and over.
     */
    suspend fun better(entry: QueueSong, session: ServerSession, force: Boolean = false): QueueSong? {
        if (LocalMusic.isLocal(entry.serverId)) return null
        val key = "${entry.serverId}/${entry.song.id}/${entry.song.size}"
        if (!force) checked[key]?.takeIf { System.currentTimeMillis() - it < RECHECK_MS }?.let { return null }
        val result = if (YouTubeMusic.isYouTube(entry.serverId)) {
            runCatching { api.findSong(entry.song.ref(), session) }.getOrNull()
        } else if (entry.serverId == session.id) {
            val current = runCatching { api.song(entry.song.id, session) }.getOrNull()
                ?: runCatching { api.findSong(entry.song.ref(), session) }.getOrNull()
            current?.takeIf { changed(entry.song, it) }
        } else {
            null
        }
        if (result == null) checked[key] = System.currentTimeMillis()
        return result?.let { QueueSong(session.id, it) }
    }

    companion object {
        private const val RECHECK_MS = 10 * 60_000L

        private fun Song.ref() = TrackRef(title, artistLabel, album, duration)

        /** The server has a different file for the song than the one [old] describes. */
        fun changed(old: Song, new: Song): Boolean =
            new.id != old.id || new.suffix != old.suffix || (new.size != null && old.size != null && new.size != old.size)

        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")

        fun lossless(song: Song) = song.suffix?.lowercase() in LOSSLESS

        /** Worth replacing a download: lossless instead of lossy, or more bits or a higher rate. */
        fun isUpgrade(old: Song, new: Song): Boolean = when {
            lossless(new) && !lossless(old) -> true
            lossless(new) && lossless(old) -> (new.bitDepth ?: 16) > (old.bitDepth ?: 16) || (new.samplingRate ?: 44_100) > (old.samplingRate ?: 44_100)
            else -> !lossless(old) && (new.bitRate ?: 0) > (old.bitRate ?: 0) + 32
        }
    }
}
