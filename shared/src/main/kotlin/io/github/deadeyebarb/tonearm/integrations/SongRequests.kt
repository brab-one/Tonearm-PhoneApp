package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig

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
 * Asks Lidarr for one song you liked or requested: the album it's on (named by the source, else found
 * through MusicBrainz), so only that album downloads, never the artist's whole discography.
 */
class SongRequests(private val lidarr: LidarrClient, private val musicBrainz: MusicBrainz) {

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
        album(config, key, ref, artist, artistId = known?.foreignId?.takeIf { it.isNotBlank() })?.let { return it }
        if (!artistFallback || known == null) return SongRequestResult.NotFound("the album of “${ref.title}” by $artist")
        if (known.inLidarr) return SongRequestResult.AlreadyWanted(known.title)
        lidarr.addArtist(config, key, known, lidarr.resolveDefaults(config, key))
        return SongRequestResult.Artist(known.title)
    }

    /** Asks Lidarr for one album by name (from YouTube Music, an import…). */
    suspend fun requestAlbum(config: LidarrConfig, key: String, title: String, artist: String): SongRequestResult =
        album(config, key, TrackRef(title = "", artist = artist, album = title), SongMatch.cleanArtist(artist), musicBrainz = false)
            ?: SongRequestResult.NotFound("$title by $artist")

    private suspend fun album(
        config: LidarrConfig,
        key: String,
        ref: TrackRef,
        artist: String,
        musicBrainz: Boolean = true,
        artistId: String? = null,
    ): SongRequestResult? {
        val primary = SongMatch.primaryArtist(artist)
        val byName = ref.album?.takeIf { it.isNotBlank() }?.let { name ->
            lidarr.lookupAlbum(config, key, "$primary $name").firstOrNull { candidate ->
                Names.normalize(SongMatch.cleanTitle(candidate.title)) == Names.normalize(SongMatch.cleanTitle(name)) &&
                    Names.normalize(candidate.artistName.orEmpty()).let { it == Names.normalize(primary) || it == Names.normalize(artist) }
            }
        }
        val candidate = byName ?: (if (musicBrainz) runCatching { this.musicBrainz.albumOf(ref, artistId) }.getOrNull() else null)?.let { group ->
            lidarr.lookupAlbum(config, key, "lidarr:${group.id}").firstOrNull { it.foreignId == group.id }
        } ?: return null
        val name = candidate.artistName ?: primary
        if (candidate.inLidarr) {
            if (candidate.monitored) return SongRequestResult.AlreadyWanted(candidate.title)
            lidarr.monitorAlbum(config, key, candidate.lidarrId, search = config.searchOnAdd)
        } else {
            lidarr.addAlbum(config, key, candidate.candidate, lidarr.resolveDefaults(config, key))
        }
        return SongRequestResult.Album(candidate.title, name)
    }
}
