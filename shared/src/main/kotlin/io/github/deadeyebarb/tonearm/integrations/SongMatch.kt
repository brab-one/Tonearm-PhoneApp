package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A song known only by its tags: from an imported playlist, YouTube Music or Spotify. */
@Serializable
data class TrackRef(
    val title: String,
    val artist: String,
    val album: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    /** YouTube video id, when the song came from YouTube (Music). */
    val youtubeId: String? = null,
    val coverUrl: String? = null,
)

/** Finds a library song for a [TrackRef], tolerating the usual differences between services' tags. */
object SongMatch {
    private val decorations = listOf(
        Regex("""\s*[(\[](?:feat|ft|featuring|with)\.?\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[(\[](?:official\s*(?:music\s*)?(?:video|audio|lyric video|visualizer)|lyrics?|audio|hd|hq|4k|explicit|clean)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[(\[][^)\]]*(?:remaster(?:ed)?|deluxe|anniversary|mono|stereo)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s+-\s+(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(?:feat|ft|featuring)\.?\s.*$""", RegexOption.IGNORE_CASE),
    )

    /** A title without featuring credits, "(Official Video)", remaster notes and the like. */
    fun cleanTitle(title: String): String = decorations.fold(title) { t, r -> r.replace(t, "") }.trim()

    /** A credited artist without YouTube's " - Topic" and "VEVO" channel suffixes. */
    fun cleanArtist(artist: String): String =
        artist.removeSuffix(" - Topic").replace(Regex("(?i)VEVO$"), "").replace(Regex("(?i)\\s+official$"), "").trim()

    /** The first artist of a credit like "A, B & C" or "A feat. B". */
    fun primaryArtist(artist: String): String =
        artist.split(Regex("""\s*(?:,|;|&|\bfeat\.?|\bft\.?|\bfeaturing\b|\bx\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim() ?: artist

    /**
     * A YouTube upload as a song. "Artist - Topic" channels have proper titles; for other channels a
     * title like "Artist - Title (Official Video)" is split, since the channel name may be a label's.
     */
    fun fromYouTube(title: String, uploader: String?, duration: Int?, videoId: String?, cover: String?): TrackRef {
        val channel = uploader.orEmpty()
        val isTopic = channel.endsWith(" - Topic")
        val dash = Regex("""^(.+?)\s+[-–—]\s+(.+)$""").find(title)
        val (artist, name) = if (!isTopic && dash != null) dash.groupValues[1] to dash.groupValues[2] else cleanArtist(channel) to title
        return TrackRef(cleanTitle(name), cleanArtist(artist), duration = duration, youtubeId = videoId, coverUrl = cover)
    }

    /** How well [song] matches [ref]: null when it doesn't, higher is better. */
    fun score(song: Song, ref: TrackRef): Int? {
        if (Names.normalize(cleanTitle(song.title)) != Names.normalize(cleanTitle(ref.title))) return null
        val songArtist = Names.normalize(song.artistLabel.ifEmpty { song.artist.orEmpty() })
        val refArtist = Names.normalize(cleanArtist(ref.artist))
        val refPrimary = Names.normalize(primaryArtist(cleanArtist(ref.artist)))
        val artistScore = when {
            songArtist == refArtist -> 3
            songArtist.isNotEmpty() && (songArtist.contains(refPrimary) || refPrimary.contains(songArtist)) -> 2
            else -> return null
        }
        val albumScore = if (ref.album != null && song.album != null && Names.normalize(ref.album) == Names.normalize(song.album)) 2 else 0
        val durationScore = when {
            ref.duration == null || song.duration == null -> 0
            abs(ref.duration - song.duration) <= 3 -> 2
            abs(ref.duration - song.duration) <= 15 -> 1
            // Same name, very different length: likely a live or extended version.
            else -> -2
        }
        return artistScore + albumScore + durationScore
    }

    fun best(candidates: List<Song>, ref: TrackRef): Song? =
        candidates.mapNotNull { song -> score(song, ref)?.let { song to it } }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
}
