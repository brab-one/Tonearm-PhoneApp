package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What asking Lidarr for a song did. */
sealed interface SongRequestResult {
    val message: String

    data class Album(val title: String, val artist: String) : SongRequestResult {
        override val message get() = "Requested $title by $artist in Lidarr"
    }
    data class Artist(val name: String) : SongRequestResult {
        override val message get() = "Requested $name in Lidarr"
    }
    data class AlreadyWanted(val what: String) : SongRequestResult {
        override val message get() = "Lidarr already has $what"
    }
    data class NotFound(val what: String) : SongRequestResult {
        override val message get() = "Couldn't find $what, so nothing was requested"
    }
}

/**
 * Asks Lidarr for one song you liked or requested: the album it's on, so only that album downloads, never
 * the artist's whole discography. The album is the one the source names; otherwise it's found in Lidarr's
 * own track lists (Lidarr gets them from its metadata server; nothing here asks MusicBrainz).
 */
class SongRequests(
    private val lidarr: LidarrClient,
    /** Waits between looks while Lidarr fetches a new artist's albums. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * Requests the song's album. When no album can be found nothing is requested, unless [artistFallback]
     * allows adding the whole artist (which downloads what Lidarr's monitor setting says: often everything).
     */
    suspend fun request(config: LidarrConfig, key: String, ref: TrackRef, artistFallback: Boolean = false): SongRequestResult {
        val artist = SongMatch.cleanArtist(ref.artist)
        val names = listOf(artist, SongMatch.primaryArtist(artist)).distinct()
        val known = names.firstNotNullOfOrNull { name ->
            runCatching { lidarr.lookupArtist(config, key, name) }.getOrDefault(emptyList()).firstOrNull { Names.normalize(it.title) == Names.normalize(name) }
        }
        byName(config, key, ref, artist)?.let { return want(config, key, it, SongMatch.primaryArtist(artist)) }
        if (known != null) fromTracks(config, key, ref, known)?.let { return it }
        if (!artistFallback || known == null) return SongRequestResult.NotFound("the album of “${ref.title}” by $artist")
        if (known.inLidarr) return SongRequestResult.AlreadyWanted(known.title)
        lidarr.addArtist(config, key, known, lidarr.resolveDefaults(config, key))
        return SongRequestResult.Artist(known.title)
    }

    /** Asks Lidarr for one album by name (from YouTube Music, an import…). */
    suspend fun requestAlbum(config: LidarrConfig, key: String, title: String, artist: String): SongRequestResult {
        val clean = SongMatch.cleanArtist(artist)
        val hit = byName(config, key, TrackRef(title = "", artist = artist, album = title), clean) ?: return SongRequestResult.NotFound("$title by $artist")
        return want(config, key, hit, SongMatch.primaryArtist(clean))
    }

    private suspend fun byName(config: LidarrConfig, key: String, ref: TrackRef, artist: String): LidarrClient.AlbumHit? {
        val primary = SongMatch.primaryArtist(artist)
        val name = ref.album?.takeIf { it.isNotBlank() } ?: return null
        return lidarr.lookupAlbum(config, key, "$primary $name").firstOrNull { candidate ->
            Names.normalize(SongMatch.cleanTitle(candidate.title)) == Names.normalize(SongMatch.cleanTitle(name)) &&
                Names.normalize(candidate.artistName.orEmpty()).let { it == Names.normalize(primary) || it == Names.normalize(artist) }
        }
    }

    private suspend fun want(config: LidarrConfig, key: String, album: LidarrClient.AlbumHit, artist: String): SongRequestResult {
        if (album.inLidarr) {
            if (album.monitored) return SongRequestResult.AlreadyWanted(album.title)
            lidarr.monitorAlbum(config, key, album.lidarrId, search = config.searchOnAdd)
        } else {
            lidarr.addAlbum(config, key, album.candidate, lidarr.resolveDefaults(config, key))
        }
        return SongRequestResult.Album(album.title, album.artistName ?: artist)
    }

    /**
     * Finds the song in the albums Lidarr knows for [artist]. A new artist goes in unmonitored first (nothing
     * downloads), and comes out again when the song isn't on any of its albums.
     */
    private suspend fun fromTracks(config: LidarrConfig, key: String, ref: TrackRef, artist: LidarrCandidate): SongRequestResult? {
        val existing = if (artist.inLidarr) lidarr.artists(config, key).firstOrNull { it.foreignArtistId == artist.foreignId } else null
        val added = if (existing == null) {
            lidarr.addArtist(config, key, artist, lidarr.resolveDefaults(config, key).copy(monitor = "none", search = false))
        } else null
        val artistId = (existing ?: added)?.id ?: return null
        val tracks = settledTracks(config, key, artistId, waitForMetadata = added != null)
        val title = Names.normalize(SongMatch.cleanTitle(ref.title))
        val albumIds = tracks.filter { Names.normalize(SongMatch.cleanTitle(it.title)) == title }.map { it.albumId }.toSet()
        val album = lidarr.albums(config, key, artistId).filter { it.id in albumIds }.minWithOrNull(compareBy({ rank(it) }, { it.releaseDate ?: "9999" }))
        if (album == null) {
            // Through the Tonearm server without admin rights this isn't allowed; the artist then stays, unmonitored.
            if (added != null) runCatching { lidarr.deleteArtist(config, key, added.id, deleteFiles = false, exclude = false) }
            return null
        }
        if (album.monitored) return SongRequestResult.AlreadyWanted(album.title)
        lidarr.monitorAlbum(config, key, album.id, search = config.searchOnAdd)
        return SongRequestResult.Album(album.title, (existing ?: added)?.artistName?.ifEmpty { null } ?: artist.title)
    }

    /** The artist's tracks; for a just-added artist, once Lidarr has stopped adding to them (or after a while). */
    private suspend fun settledTracks(config: LidarrConfig, key: String, artistId: Int, waitForMetadata: Boolean): List<LidarrTrack> {
        var tracks = lidarr.tracks(config, key, artistId)
        if (!waitForMetadata) return tracks
        var waited = 0L
        var steady = 0
        while (waited < METADATA_WAIT_MS && steady < 2) {
            pause(POLL_MS)
            waited += POLL_MS
            val next = lidarr.tracks(config, key, artistId)
            steady = if (next.isNotEmpty() && next.size == tracks.size) steady + 1 else 0
            tracks = next
        }
        return tracks
    }

    /** Studio albums first, then EPs, then singles; compilations, live albums and the like last. */
    private fun rank(album: LidarrAlbum): Int {
        val secondary = album.secondaryTypes.mapNotNull { type ->
            (type as? JsonPrimitive)?.contentOrNull ?: (type as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.filterNot { it.equals("Studio", true) }
        return when {
            secondary.isNotEmpty() -> 3
            album.albumType.equals("Album", true) -> 0
            album.albumType.equals("EP", true) -> 1
            else -> 2
        }
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val METADATA_WAIT_MS = 90_000L
    }
}
