package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.Starred
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class ForYouKind { PICK, LIKED, REDISCOVER }

/** An album on Home's "For you" row, and the line that says why ("LIKED 4 DAYS AGO"). */
data class ForYouItem(val album: Album, val kind: ForYouKind, val caption: String)

/**
 * Home's "For you" row, from the music server alone: this week's picks, albums liked lately, and albums played a lot
 * that haven't been played in months. Works without the Tonearm server (there are no picks then).
 */
class ForYou(private val api: SubsonicApi, private val weekly: WeeklyPicks) {
    suspend fun build(session: ServerSession, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): List<ForYouItem> =
        coroutineScope {
            val picks = async { runCatching { pickAlbums(session) }.getOrDefault(emptyList()) }
            val starred = async { runCatching { api.starred(session) }.getOrNull() }
            val frequent = async { runCatching { api.albumList(AlbumListType.FREQUENT, 100, session = session) }.getOrDefault(emptyList()) }
            mix(picks.await(), starred.await(), frequent.await(), today, now)
        }

    /** The albums of the user's newest ready weekly picks, with when the week ends. */
    private suspend fun pickAlbums(session: ServerSession): List<Pair<Album, Long>> {
        val week = weekly.batches(session).filter { it.state.status == WeeklyState.READY }.maxByOrNull { it.state.created } ?: return emptyList()
        return api.playlist(week.playlist.id, session).entry.filter { it.albumId != null }.groupBy { it.albumId }
            .map { (_, songs) -> albumOf(songs.first()) to week.expires }
    }

    companion object {
        const val PICKS = 4
        const val LIKED = 6
        const val REDISCOVER = 6
        const val SIZE = 15
        private const val PER_ARTIST = 2
        private const val LIKED_DAYS = 60L
        private const val FORGOTTEN_DAYS = 90L
        private const val MIN_PLAYS = 3
        private const val DAY_MS = 24 * 3_600_000L

        /**
         * Everything the row shows, in order: picks, liked and forgotten albums by turns, each album once (also when
         * another copy has another id), at most two per artist. Picks win over likes, likes over forgotten ones.
         */
        fun mix(
            picks: List<Pair<Album, Long>>,
            starred: Starred?,
            frequent: List<Album>,
            today: LocalDate,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<ForYouItem> {
            val seen = HashSet<String>()
            /** The first [n] albums not shown yet. */
            fun <T> List<Pair<Album, T>>.fresh(n: Int) = asSequence().filter { (album, _) ->
                val keys = listOf("id:" + album.id, "name:" + nameKey(album))
                keys.none { it in seen }.also { if (it) seen += keys }
            }.take(n).toList()

            val pickItems = picks.fresh(PICKS).map { (album, expires) -> ForYouItem(album, ForYouKind.PICK, pickCaption(expires, now)) }

            val likedSince = now - LIKED_DAYS * DAY_MS
            val liked = (starred?.song.orEmpty().filter { it.albumId != null }.map { albumOf(it) to it.starred } +
                starred?.album.orEmpty().map { it to it.starred })
                .mapNotNull { (album, at) -> at?.let(::millis)?.takeIf { it >= likedSince }?.let { album to it } }
                .sortedByDescending { it.second }
            val likedItems = liked.fresh(LIKED).map { (album, at) -> ForYouItem(album, ForYouKind.LIKED, likedCaption(at, today, zone)) }

            // The same handful all day, others tomorrow.
            val forgottenBefore = now - FORGOTTEN_DAYS * DAY_MS
            val forgotten = frequent.filter { it.playCount >= MIN_PLAYS }
                .mapNotNull { album -> album.played?.let(::millis)?.takeIf { it < forgottenBefore }?.let { album to it } }
                .sortedByDescending { it.first.playCount }.take(30).shuffled(Random(today.toEpochDay()))
            val forgottenItems = forgotten.fresh(REDISCOVER)
                .map { (album, at) -> ForYouItem(album, ForYouKind.REDISCOVER, rediscoverCaption(album.playCount, at, today, zone)) }

            val perArtist = HashMap<String, Int>()
            return DailyDiscovery.interleave(listOf(pickItems, likedItems, forgottenItems))
                .filter { item -> perArtist.merge(artistKey(item.album), 1, Int::plus)!! <= PER_ARTIST }
                .take(SIZE)
        }

        /** How long the week's picks stay: "3 DAYS LEFT", or "LAST DAY". */
        fun pickCaption(expires: Long, now: Long): String {
            val days = Math.floorDiv(expires - now + DAY_MS - 1, DAY_MS)
            return if (days <= 1) "LAST DAY" else "$days DAYS LEFT"
        }

        /** "LIKED TODAY", "LIKED YESTERDAY", "LIKED 4 DAYS AGO". */
        fun likedCaption(at: Long, today: LocalDate, zone: ZoneId): String =
            when (val days = today.toEpochDay() - Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()) {
                in Long.MIN_VALUE..0L -> "LIKED TODAY"
                1L -> "LIKED YESTERDAY"
                else -> "LIKED $days DAYS AGO"
            }

        /** "14 PLAYS // LAST IN MAR", with the year when it isn't this one. */
        fun rediscoverCaption(plays: Long, at: Long, today: LocalDate, zone: ZoneId): String {
            val last = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            val month = last.format(DateTimeFormatter.ofPattern(if (last.year == today.year) "MMM" else "MMM yyyy", Locale.ENGLISH))
            return "$plays PLAYS // LAST IN ${month.uppercase(Locale.ROOT)}"
        }

        private fun albumOf(song: Song) =
            Album(song.albumId!!, song.album.orEmpty(), song.artist, song.artistId, coverArt = song.coverArt, year = song.year)

        private fun nameKey(album: Album) = Names.normalize(album.artistLabel) + "|" + Names.normalize(SongMatch.cleanTitle(album.name))

        private fun artistKey(album: Album) = Names.normalize(SongMatch.primaryArtist(album.artistLabel))

        private fun millis(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }
}
