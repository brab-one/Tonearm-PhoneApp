package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi

/**
 * Deleting music for good, through Lidarr: it deletes the files, and the music server drops them at its next
 * scan. Music Lidarr doesn't manage, or that can't be told apart from other music for sure, isn't deleted (the
 * calls say so by returning false).
 */
class MusicRemoval(private val client: LidarrClient) {
    /**
     * An artist with all their files, when exactly one Lidarr artist has this very name: a page like "A & B" never
     * deletes A. Lidarr keeps them excluded, so its import lists don't add them back.
     */
    suspend fun artist(config: LidarrConfig, key: String, name: String): Boolean {
        val wanted = Names.normalize(name)
        if (wanted.isEmpty()) return false
        val found = client.artists(config, key).filter { Names.normalize(it.artistName) == wanted }.singleOrNull() ?: return false
        client.deleteArtist(config, key, found.id, deleteFiles = true, exclude = true)
        return true
    }

    /**
     * One album with its files; [year] tells same-titled ones apart. Lidarr keeps it excluded, or its next look at
     * the artist would add it again and download it (asking for it again lifts that).
     */
    suspend fun album(config: LidarrConfig, key: String, artist: String, album: String, year: Int? = null): Boolean {
        val found = findAlbums(config, key, listOf(artist), album, year).singleOrNull() ?: return false
        client.deleteAlbum(config, key, found.id, deleteFiles = true, exclude = true)
        return true
    }

    /**
     * One song's file. Lidarr then stops watching its album, or it would fetch the song again (the album's other
     * songs stay). [artists] are names to find the album under, the album's own artist first. Only a song whose
     * title matches is deleted, and only when it's the one such song: [track], [disc] and [year] pick between
     * songs and albums with the same title.
     */
    suspend fun song(config: LidarrConfig, key: String, artists: List<String>, album: String, title: String, track: Int?, disc: Int?, year: Int? = null): Boolean {
        val wanted = Names.normalize(SongMatch.cleanTitle(title))
        val same = findAlbums(config, key, artists, album, year).flatMap { found ->
            client.albumTracks(config, key, found.id)
                .filter { it.hasFile && it.trackFileId > 0 && Names.normalize(SongMatch.cleanTitle(it.title)) == wanted }
                .map { found to it }
        }
        val (found, hit) = same.singleOrNull()
            ?: same.singleOrNull { (_, t) -> track != null && t.trackNumber.toIntOrNull() == track && (disc == null || t.mediumNumber == disc) }
            ?: return false
        // Not watched first: when deleting then fails, nothing is lost.
        client.unmonitorAlbum(config, key, found.id)
        client.deleteTrackFile(config, key, hit.trackFileId)
        return true
    }

    /** A library song by its id on the music server, looked for in Lidarr under its album's own artist first. */
    suspend fun librarySong(config: LidarrConfig, key: String, api: SubsonicApi, songId: String): Boolean {
        val song = api.song(songId)
        val album = song.album ?: return false
        val albumInfo = song.albumId?.let { runCatching { api.album(it) }.getOrNull() }
        val artists = listOfNotNull(albumInfo?.artistLabel, song.artistLabel, song.artist).filter { it.isNotBlank() }.distinct()
        return song(config, key, artists, album, song.title, song.track, song.discNumber, albumInfo?.year ?: song.year)
    }

    /**
     * Lidarr's albums titled [album] by these artists. Artists with these very names come first; only when none of
     * them has it, the first artist of a credit like "A & B" is tried. An album with this very title wins over
     * ones that only match without "(Deluxe Edition)" and the like, and [year] picks between same-titled ones.
     */
    private suspend fun findAlbums(config: LidarrConfig, key: String, artists: List<String>, album: String, year: Int?): List<LidarrAlbum> {
        val all = client.artists(config, key)
        val tiers = listOf(artists.map(Names::normalize), artists.map { Names.normalize(SongMatch.primaryArtist(it)) })
        val seen = mutableSetOf<Int>()
        for (names in tiers) {
            val under = all.filter { Names.normalize(it.artistName) in names.filter(String::isNotEmpty) && seen.add(it.id) }
            val albums = under.flatMap { client.albums(config, key, it.id) }
            val exact = albums.filter { Names.normalize(it.title) == Names.normalize(album) }
            val found = exact.ifEmpty { albums.filter { Names.normalize(SongMatch.cleanTitle(it.title)) == Names.normalize(SongMatch.cleanTitle(album)) } }
            if (found.isEmpty()) continue
            if (found.size == 1 || year == null) return found
            return found.filter { it.releaseDate?.take(4)?.toIntOrNull() == year }.ifEmpty { found }
        }
        return emptyList()
    }
}
