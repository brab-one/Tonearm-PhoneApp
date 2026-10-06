package io.github.deadeyebarb.tonearm.subsonic

import io.github.deadeyebarb.tonearm.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class SubsonicApiException(val code: Int, message: String) : IOException(message)

class NoServerException : IOException("No server is set up yet")
class SubsonicHttpException(val code: Int, val body: String) : IOException("HTTP $code")
class SubsonicProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Subsonic / OpenSubsonic REST client. Calls without an explicit session go to the active server.
 * See https://opensubsonic.netlify.app/docs/ for the endpoints.
 */
/** Where calls go when no session is given: the server currently in use. */
fun interface ActiveServer {
    suspend fun awaitActive(): ServerSession
}

class SubsonicApi(private val sessions: ActiveServer, private val json: Json) {
    private val extensionCache = ConcurrentHashMap<String, Set<String>>()

    suspend fun active(): ServerSession = sessions.awaitActive()

    suspend fun call(session: ServerSession, method: String, params: List<Pair<String, Any?>> = emptyList()): SubsonicResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(session.apiUrl(method, params)).build()
            val body = session.client.newCall(request).await().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw SubsonicHttpException(response.code, text.take(500))
                text
            }
            parse(json, body)
        }

    private suspend fun call(method: String, vararg params: Pair<String, Any?>) = call(active(), method, params.toList())

    suspend fun ping(session: ServerSession): SubsonicResponse = call(session, "ping")

    suspend fun extensions(session: ServerSession): Set<String> =
        extensionCache[session.id] ?: runCatching {
            call(session, "getOpenSubsonicExtensions").openSubsonicExtensions.orEmpty().map { it.name }.toSet()
        }.getOrDefault(emptySet()).also { extensionCache[session.id] = it }

    /** An empty library is an empty list here: Navidrome reports it as error 70 ("Library not found or empty"). */
    suspend fun artists(session: ServerSession? = null): List<ArtistIndexEntry> = try {
        call(session ?: active(), "getArtists").artists?.index.orEmpty()
    } catch (e: SubsonicApiException) {
        if (e.code == 70) emptyList() else throw e
    }

    suspend fun artist(id: String, session: ServerSession? = null): Artist =
        call(session ?: active(), "getArtist", listOf("id" to id)).artist ?: notFound()

    /** Bio and similar artists; with [includeNotPresent], also similar artists you don't own (see [Artist.inLibrary]). */
    suspend fun artistInfo(id: String, includeNotPresent: Boolean = false, count: Int = 20): ArtistInfo? = runCatching {
        call("getArtistInfo2", "id" to id, "count" to count, "includeNotPresent" to includeNotPresent.takeIf { it }).artistInfo2
    }.getOrNull()

    suspend fun album(id: String, session: ServerSession? = null): Album =
        call(session ?: active(), "getAlbum", listOf("id" to id)).album ?: notFound()

    suspend fun albumList(
        type: AlbumListType,
        size: Int = 40,
        offset: Int = 0,
        session: ServerSession? = null,
    ): List<Album> = call(
        session ?: active(), "getAlbumList2",
        listOf("type" to type.param, "size" to size, "offset" to offset),
    ).albumList2?.album.orEmpty()

    suspend fun albumsByGenre(genre: String, size: Int = 40, offset: Int = 0): List<Album> =
        call("getAlbumList2", "type" to "byGenre", "genre" to genre, "size" to size, "offset" to offset).albumList2?.album.orEmpty()

    suspend fun song(id: String, session: ServerSession? = null): Song =
        call(session ?: active(), "getSong", listOf("id" to id)).song ?: notFound()

    suspend fun randomSongs(size: Int = 100, session: ServerSession? = null): List<Song> =
        call(session ?: active(), "getRandomSongs", listOf("size" to size)).randomSongs?.song.orEmpty()

    suspend fun songsByGenre(genre: String, count: Int = 100, offset: Int = 0, session: ServerSession? = null): List<Song> =
        call(session ?: active(), "getSongsByGenre", listOf("genre" to genre, "count" to count, "offset" to offset))
            .songsByGenre?.song.orEmpty()

    suspend fun topSongs(artistName: String, count: Int = 10): List<Song> =
        runCatching { call("getTopSongs", "artist" to artistName, "count" to count).topSongs?.song.orEmpty() }.getOrDefault(emptyList())

    suspend fun similarSongs(id: String, count: Int = 50): List<Song> =
        call("getSimilarSongs2", "id" to id, "count" to count).similarSongs2?.song.orEmpty()

    suspend fun starred(session: ServerSession? = null): Starred =
        call(session ?: active(), "getStarred2").starred2 ?: Starred()

    suspend fun search(
        query: String,
        artistCount: Int = 10,
        albumCount: Int = 20,
        songCount: Int = 50,
        session: ServerSession? = null,
    ): SearchResult = call(
        session ?: active(), "search3",
        listOf("query" to query, "artistCount" to artistCount, "albumCount" to albumCount, "songCount" to songCount),
    ).searchResult3 ?: SearchResult()

    suspend fun playlists(session: ServerSession? = null): List<Playlist> =
        call(session ?: active(), "getPlaylists").playlists?.playlist.orEmpty()

    suspend fun playlist(id: String, session: ServerSession? = null): Playlist =
        call(session ?: active(), "getPlaylist", listOf("id" to id)).playlist ?: notFound()

    suspend fun createPlaylist(name: String, songIds: List<String>, session: ServerSession? = null): Playlist? =
        call(session ?: active(), "createPlaylist", listOf("name" to name) + songIds.map { "songId" to it }).playlist

    /** Replaces all songs of an existing playlist (createPlaylist with a playlistId). */
    suspend fun replacePlaylist(playlistId: String, songIds: List<String>, session: ServerSession): Playlist? =
        call(session, "createPlaylist", listOf("playlistId" to playlistId) + songIds.map { "songId" to it }).playlist

    suspend fun setPlaylistComment(playlistId: String, comment: String, session: ServerSession) {
        call(session, "updatePlaylist", listOf("playlistId" to playlistId, "comment" to comment))
    }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        call(active(), "updatePlaylist", listOf("playlistId" to playlistId) + songIds.map { "songIdToAdd" to it })
    }

    suspend fun removeFromPlaylist(playlistId: String, indexes: List<Int>) {
        call(active(), "updatePlaylist", listOf("playlistId" to playlistId) + indexes.map { "songIndexToRemove" to it })
    }

    suspend fun renamePlaylist(playlistId: String, name: String, session: ServerSession? = null) {
        call(session ?: active(), "updatePlaylist", listOf("playlistId" to playlistId, "name" to name))
    }

    suspend fun deletePlaylist(playlistId: String, session: ServerSession? = null) {
        call(session ?: active(), "deletePlaylist", listOf("id" to playlistId))
    }

    /** Has the server look for added and removed files now (admins only). */
    suspend fun startScan(session: ServerSession? = null) {
        call(session ?: active(), "startScan")
    }

    suspend fun genres(session: ServerSession? = null): List<Genre> = call(session ?: active(), "getGenres").genres?.genre.orEmpty()

    suspend fun setStarred(session: ServerSession, kind: StarKind, id: String, starred: Boolean) {
        call(session, if (starred) "star" else "unstar", listOf(kind.param to id))
    }

    suspend fun scrobble(session: ServerSession, songId: String, timeMs: Long, submission: Boolean) {
        call(session, "scrobble", listOf("id" to songId, "time" to timeMs, "submission" to submission))
    }

    /** Prefers OpenSubsonic structured (often synced) lyrics, then the classic plain-text endpoint. */
    suspend fun lyrics(session: ServerSession, song: Song): Lyrics? {
        if ("songLyrics" in extensions(session)) {
            val structured = runCatching {
                call(session, "getLyricsBySongId", listOf("id" to song.id)).lyricsList?.structuredLyrics.orEmpty()
            }.getOrDefault(emptyList())
            val best = structured.firstOrNull { it.synced && it.line.isNotEmpty() } ?: structured.firstOrNull { it.line.isNotEmpty() }
            if (best != null) return Lyrics.from(best)
        }
        val artist = song.artist ?: return null
        val plain = runCatching {
            call(session, "getLyrics", listOf("artist" to artist, "title" to song.title)).lyrics?.value
        }.getOrNull()
        return plain?.takeIf { it.isNotBlank() }?.let(Lyrics::parse)
    }

    private fun notFound(): Nothing = throw SubsonicApiException(70, "Not found")

    companion object {
        fun parse(json: Json, body: String): SubsonicResponse {
            if (!body.trimStart().startsWith("{")) {
                throw SubsonicProtocolException("The server didn't answer with Subsonic JSON. Check the URL.")
            }
            val response = try {
                json.decodeFromString(Envelope.serializer(), body).response
            } catch (e: SerializationException) {
                throw SubsonicProtocolException("Unexpected response from the server: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw SubsonicProtocolException("Unexpected response from the server: ${e.message}", e)
            }
            if (response.status != "ok") {
                val error = response.error
                throw SubsonicApiException(error?.code ?: 0, error?.message ?: "The server reported an error")
            }
            return response
        }
    }
}
