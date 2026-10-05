package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.likes.findSong
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A song to append: from the library, or from YouTube Music ([youtube]). */
data class NextSong(val song: Song, val youtube: Boolean)

/**
 * What to play when the queue runs out: music like the last song (the server's similar songs, then
 * YouTube Music's radio with library copies swapped in), or another of your playlists.
 */
class Continuation(private val api: SubsonicApi, private val youtube: YouTubeMusic) {

    /** Songs like [last]. [played] holds keys from [key] to leave out. */
    suspend fun similar(last: Song, lastIsYouTube: Boolean, session: ServerSession?, played: Set<String>, allowYouTube: Boolean): List<NextSong> {
        val fromLibrary = if (session != null && !lastIsYouTube) {
            runCatching { api.similarSongs(last.id, 40) }.getOrDefault(emptyList()).map { NextSong(it, false) }.filterNot { key(it) in played }
        } else {
            emptyList()
        }
        if (fromLibrary.size >= MIN_SONGS || !allowYouTube) return fromLibrary.take(MAX_SONGS)
        val videoId = if (lastIsYouTube) {
            last.id
        } else {
            runCatching { youtube.searchSongs("${last.artistLabel} ${SongMatch.cleanTitle(last.title)}", 5) }.getOrDefault(emptyList())
                .firstOrNull { Names.normalize(SongMatch.cleanTitle(it.title)) == Names.normalize(SongMatch.cleanTitle(last.title)) }?.id
        }
        val radio = when {
            videoId != null -> runCatching { youtube.radio(videoId, last.artistLabel, 30) }.getOrDefault(emptyList())
            else -> runCatching { youtube.artistSongs(last.artistLabel, 25) }.getOrDefault(emptyList())
        }
        val gate = Semaphore(4)
        val swapped = coroutineScope {
            radio.map { song ->
                async {
                    val inLibrary = session?.let {
                        gate.withPermit { runCatching { api.findSong(TrackRef(song.title, song.artist.orEmpty(), duration = song.duration), it) }.getOrNull() }
                    }
                    if (inLibrary != null) NextSong(inLibrary, false) else NextSong(song, true)
                }
            }.awaitAll()
        }
        return (fromLibrary + swapped).filterNot { key(it) in played }.distinctBy(::key).take(MAX_SONGS)
    }

    /** Another playlist that isn't mostly what just played. */
    suspend fun anotherPlaylist(session: ServerSession, played: Set<String>): List<NextSong> {
        val playlists = runCatching { api.playlists(session) }.getOrDefault(emptyList()).filter { it.songCount > 0 }.shuffled()
        for (candidate in playlists) {
            val songs = runCatching { api.playlist(candidate.id, session).entry }.getOrDefault(emptyList()).map { NextSong(it, false) }
            if (songs.isNotEmpty() && songs.count { key(it) !in played } >= songs.size / 2) return songs
        }
        return emptyList()
    }

    companion object {
        private const val MIN_SONGS = 10
        private const val MAX_SONGS = 40

        fun key(song: NextSong) = key(song.song.id, song.youtube)
        fun key(id: String, youtube: Boolean) = (if (youtube) "yt/" else "lib/") + id
    }
}
