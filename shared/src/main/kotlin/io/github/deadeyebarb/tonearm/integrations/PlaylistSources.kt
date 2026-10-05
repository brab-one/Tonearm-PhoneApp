package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/** A playlist read from another service or an export file. */
data class ImportedPlaylist(
    val name: String,
    val tracks: List<TrackRef>,
    /** The source listed fewer songs than the playlist has (Spotify's public page stops at 100). */
    val truncated: Boolean = false,
)

/**
 * Reads playlists from YouTube Music / YouTube (through NewPipeExtractor), Spotify links (the public
 * embed page, no account needed, at most 100 songs), and export files: CSV from Exportify,
 * TuneMyMusic, Soundiiz and similar tools, and Spotify's own data export (Playlist1.json).
 */
class PlaylistSources(private val client: OkHttpClient, private val youtube: YouTubeMusic) {

    suspend fun fromUrl(url: String): ImportedPlaylist {
        val trimmed = url.trim()
        return when {
            spotifyPlaylistId(trimmed) != null -> spotify(spotifyPlaylistId(trimmed)!!)
            youtubePlaylistId(trimmed) != null -> youtube(youtubePlaylistId(trimmed)!!)
            else -> throw IOException("That isn't a YouTube Music, YouTube or Spotify playlist link")
        }
    }

    private suspend fun youtube(listId: String): ImportedPlaylist = withContext(Dispatchers.IO) {
        youtube.ensureInitialized()
        val url = "https://www.youtube.com/playlist?list=$listId"
        val info = try {
            PlaylistInfo.getInfo(ServiceList.YouTube, url)
        } catch (e: Exception) {
            throw IOException("YouTube: couldn't read that playlist (private playlists, like your own Liked songs, can't be imported)", e)
        }
        val items = info.relatedItems.filterIsInstance<StreamInfoItem>().toMutableList()
        var page = info.nextPage
        while (page != null && items.size < MAX_TRACKS) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
            items += more.items.filterIsInstance<StreamInfoItem>()
            page = more.nextPage
        }
        ImportedPlaylist(
            // An album's playlist is called "Album – <title>".
            name = info.name.removePrefix("Album – ").removePrefix("Album - ").ifBlank { "YouTube playlist" },
            tracks = items.take(MAX_TRACKS).map { item ->
                SongMatch.fromYouTube(
                    item.name, item.uploaderName, item.duration.toInt().takeIf { it > 0 },
                    YouTubeMusic.videoId(item.url), YouTubeMusic.bestThumbnail(item.thumbnails),
                )
            },
        )
    }

    private suspend fun spotify(id: String): ImportedPlaylist = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://open.spotify.com/embed/playlist/$id")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0").build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Spotify: couldn't open that playlist (HTTP ${response.code}); is it public?")
            response.body.string()
        }
        parseSpotifyEmbed(html) ?: throw IOException("Spotify: couldn't read that playlist's page")
    }

    companion object {
        const val MAX_TRACKS = 2_000
        /** What Spotify's embed page lists at most. */
        const val SPOTIFY_EMBED_LIMIT = 100

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun spotifyPlaylistId(url: String): String? =
            Regex("""(?:open\.spotify\.com/(?:intl-\w+/)?(?:embed/)?playlist/|spotify:playlist:)([A-Za-z0-9]{22})""").find(url)?.groupValues?.get(1)

        fun youtubePlaylistId(url: String): String? =
            Regex("""(?:youtube\.com|youtu\.be)/.*[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
                ?: Regex("""music\.youtube\.com/browse/VL([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)

        /** The track list in Spotify's embed page (its `__NEXT_DATA__` JSON). */
        fun parseSpotifyEmbed(html: String): ImportedPlaylist? {
            val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return null
            val entity = runCatching {
                lenient.parseToJsonElement(data).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject["state"]!!
                    .jsonObject["data"]!!.jsonObject["entity"]!!.jsonObject
            }.getOrNull() ?: return null
            val tracks = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { element ->
                val track = element as? JsonObject ?: return@mapNotNull null
                val title = track.string("title") ?: return@mapNotNull null
                TrackRef(
                    title = title,
                    artist = track.string("subtitle").orEmpty().replace(Regex(",\\s*"), ", "),
                    duration = track["duration"]?.jsonPrimitive?.longOrNull?.let { (it / 1000).toInt() },
                )
            }
            return ImportedPlaylist(
                name = entity.string("name") ?: entity.string("title") ?: "Spotify playlist",
                tracks = tracks,
                truncated = tracks.size >= SPOTIFY_EMBED_LIMIT,
            )
        }

        /**
         * A CSV export: Exportify ("Track Name", "Artist Name(s)", "Album Name", "Duration (ms)"),
         * TuneMyMusic ("Track name", "Artist name", "Album"), Soundiiz ("title", "artist", "album")…
         * Columns are found by their header.
         */
        fun parseCsv(text: String, name: String): ImportedPlaylist {
            val rows = csvRows(text.removePrefix("﻿"))
            val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return ImportedPlaylist(name, emptyList())
            fun column(vararg names: String) = header.indexOfFirst { h -> names.any { h == it } }.takeIf { it >= 0 }
                ?: header.indexOfFirst { h -> names.any { h.startsWith(it) } }.takeIf { it >= 0 }
            val title = column("track name", "track", "title", "name", "song", "song name") ?: throw IOException("No song title column in that CSV")
            val artist = column("artist name(s)", "artist name", "artists", "artist") ?: throw IOException("No artist column in that CSV")
            val album = column("album name", "album")
            val duration = column("duration (ms)", "duration_ms", "duration")
            val tracks = rows.drop(1).mapNotNull { row ->
                val t = row.getOrNull(title)?.trim().orEmpty()
                val a = row.getOrNull(artist)?.trim().orEmpty()
                if (t.isEmpty() || a.isEmpty()) return@mapNotNull null
                val d = duration?.let { row.getOrNull(it)?.trim()?.toLongOrNull() }?.let { if (it > 10_000) (it / 1000).toInt() else it.toInt() }
                TrackRef(title = t, artist = a.replace(Regex("\\s*[,;]\\s*"), ", "), album = album?.let { row.getOrNull(it)?.trim()?.ifEmpty { null } }, duration = d)
            }
            return ImportedPlaylist(name, tracks)
        }

        /** Spotify's data export ("Download your data" → Playlist1.json): every playlist in the file. */
        fun parseSpotifyDataExport(text: String): List<ImportedPlaylist> {
            val root = runCatching { lenient.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IOException("That isn't a Spotify data export file")
            val playlists = (root["playlists"] as? JsonArray) ?: throw IOException("No playlists in that file")
            return playlists.mapNotNull { element ->
                val playlist = element as? JsonObject ?: return@mapNotNull null
                val tracks = (playlist["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val track = (item as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
                    val title = track.string("trackName") ?: return@mapNotNull null
                    TrackRef(title = title, artist = track.string("artistName").orEmpty(), album = track.string("albumName"))
                }
                ImportedPlaylist(playlist.string("name") ?: "Spotify playlist", tracks).takeIf { tracks.isNotEmpty() }
            }
        }

        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        /** RFC 4180-ish CSV: quoted fields, doubled quotes, commas or semicolons as separators. */
        fun csvRows(text: String): List<List<String>> {
            val separator = if (text.lineSequence().firstOrNull().orEmpty().count { it == ';' } > text.lineSequence().firstOrNull().orEmpty().count { it == ',' }) ';' else ','
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    !quoted && c == separator -> { row += field.toString(); field.clear() }
                    !quoted && (c == '\n' || c == '\r') -> {
                        if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                        row += field.toString(); field.clear()
                        if (row.any { it.isNotEmpty() }) rows += row
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) {
                row += field.toString()
                if (row.any { it.isNotEmpty() }) rows += row
            }
            return rows
        }
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
