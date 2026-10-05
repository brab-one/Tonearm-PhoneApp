package io.github.deadeyebarb.tonearm.media

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Media IDs are slash-separated, URL-encoded segments, e.g. `song/<server>/<song>/<context>`.
 * The optional context is the container a song was browsed from (as its own media ID), so that
 * picking one song in Android Auto plays the whole album or playlist starting at that song.
 */
object MediaIds {
    const val ROOT = "root"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val ALBUMS = "albums_az"
    const val GENRES = "genres"
    const val NEWEST = "albums_newest"
    const val RECENT = "albums_recent"
    const val FREQUENT = "albums_frequent"
    const val RANDOM_ALBUMS = "albums_random"
    const val STARRED = "starred"
    const val PLAYLISTS = "playlists"
    const val ARTISTS = "artists"
    const val DOWNLOADS = "downloads"
    const val MIX = "mix"
    const val MALOJA_MIX = "maloja_mix"
    const val DAILY = "daily_discovery"
    const val BRAINARR_MIX = "brainarr_mix"

    const val SONG = "song"
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
    const val ARTIST = "artist"
    const val GENRE = "genre"

    data class Parsed(val type: String, val serverId: String? = null, val id: String? = null, val context: String? = null)

    fun song(serverId: String, songId: String, context: String? = null) = join(SONG, serverId, songId, context)
    fun album(serverId: String, albumId: String) = join(ALBUM, serverId, albumId)
    fun playlist(serverId: String, playlistId: String) = join(PLAYLIST, serverId, playlistId)
    fun artist(serverId: String, artistId: String) = join(ARTIST, serverId, artistId)
    fun genre(serverId: String, name: String) = join(GENRE, serverId, name)

    fun parse(mediaId: String): Parsed {
        val parts = mediaId.split('/').map { URLDecoder.decode(it, "UTF-8") }
        return Parsed(parts[0], parts.getOrNull(1), parts.getOrNull(2), parts.getOrNull(3))
    }

    private fun join(vararg segments: String?) =
        segments.filterNotNull().joinToString("/") { URLEncoder.encode(it, "UTF-8") }
}
