package io.github.deadeyebarb.tonearm.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class Envelope(@SerialName("subsonic-response") val response: SubsonicResponse)

/** Every endpoint's payload lives in one field of this object; the rest stay null. */
@Serializable
data class SubsonicResponse(
    val status: String = "failed",
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: SubsonicError? = null,
    val artists: ArtistsIndex? = null,
    val artist: Artist? = null,
    val album: Album? = null,
    val song: Song? = null,
    val albumList2: AlbumList? = null,
    val searchResult3: SearchResult? = null,
    val playlists: Playlists? = null,
    val playlist: Playlist? = null,
    val starred2: Starred? = null,
    val genres: Genres? = null,
    val songsByGenre: SongList? = null,
    val randomSongs: SongList? = null,
    val topSongs: SongList? = null,
    val similarSongs2: SongList? = null,
    val lyrics: PlainLyrics? = null,
    val lyricsList: LyricsList? = null,
    val artistInfo2: ArtistInfo? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtension>? = null,
)

@Serializable
data class SubsonicError(val code: Int = 0, val message: String? = null)

@Serializable
data class ArtistsIndex(val index: List<ArtistIndexEntry> = emptyList())

@Serializable
data class ArtistIndexEntry(val name: String = "", val artist: List<Artist> = emptyList())

@Serializable
data class Artist(
    /** For similar artists that aren't in the library: "-1" from Navidrome, empty from other servers. */
    val id: String = "",
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
    val artistImageUrl: String? = null,
    val album: List<Album> = emptyList(),
) {
    /** False for a similar artist the server only knows by name (see [id]). */
    val inLibrary: Boolean get() = id.isNotBlank() && id != NOT_IN_LIBRARY_ID

    companion object {
        const val NOT_IN_LIBRARY_ID = "-1"
    }
}

@Serializable
data class ArtistRef(val id: String? = null, val name: String = "")

@Serializable
data class ItemGenre(val name: String = "")

@Serializable
data class DiscTitle(val disc: Int = 0, val title: String = "")

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val displayArtist: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val playCount: Long = 0,
    val created: String? = null,
    val starred: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val genres: List<ItemGenre> = emptyList(),
    val isCompilation: Boolean? = null,
    val discTitles: List<DiscTitle> = emptyList(),
    val song: List<Song> = emptyList(),
) {
    val artistLabel: String get() = displayArtist ?: artist.orEmpty()
}

@Serializable
data class ReplayGainInfo(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGain: Float? = null,
    val fallbackGain: Float? = null,
)

/** A "Child" in Subsonic terms; we only ever deal with songs. */
@Serializable
data class Song(
    val id: String,
    val parent: String? = null,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val displayArtist: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val transcodedSuffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val channelCount: Int? = null,
    val playCount: Long? = null,
    val discNumber: Int? = null,
    val created: String? = null,
    val starred: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val replayGain: ReplayGainInfo? = null,
) {
    val artistLabel: String get() = displayArtist ?: artist.orEmpty()
}

@Serializable
data class AlbumList(val album: List<Album> = emptyList())

@Serializable
data class SongList(val song: List<Song> = emptyList())

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class Playlists(val playlist: List<Playlist> = emptyList())

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val changed: String? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class Starred(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class Genres(val genre: List<Genre> = emptyList())

@Serializable
data class Genre(val value: String = "", val songCount: Int = 0, val albumCount: Int = 0)

@Serializable
data class PlainLyrics(val artist: String? = null, val title: String? = null, val value: String? = null)

@Serializable
data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())

@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val offset: Long = 0,
    val line: List<StructuredLine> = emptyList(),
)

@Serializable
data class StructuredLine(val start: Long? = null, val value: String = "")

@Serializable
data class ArtistInfo(
    val biography: String? = null,
    val largeImageUrl: String? = null,
    val similarArtist: List<Artist> = emptyList(),
)

@Serializable
data class OpenSubsonicExtension(val name: String = "", val versions: List<Int> = emptyList())

enum class AlbumListType(val param: String, val title: String) {
    NEWEST("newest", "Recently added"),
    RECENT("recent", "Recently played"),
    FREQUENT("frequent", "Most played"),
    RANDOM("random", "Random picks"),
    STARRED("starred", "Favorite albums"),
    HIGHEST("highest", "Top rated"),
    BY_NAME("alphabeticalByName", "A–Z by title"),
    BY_ARTIST("alphabeticalByArtist", "A–Z by artist"),
}

enum class StarKind(val param: String) { SONG("id"), ALBUM("albumId"), ARTIST("artistId") }
