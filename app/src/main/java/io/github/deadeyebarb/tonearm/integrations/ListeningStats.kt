package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.AlbumCount
import io.github.deadeyebarb.tonearm.connect.Listening
import io.github.deadeyebarb.tonearm.connect.PhoneConnect
import io.github.deadeyebarb.tonearm.connect.RecentPlay
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class Period { WEEK, MONTH }

/** A calendar week or month: from [from] up to [to] (ms, [to] not included), and what to call it ("LAST WEEK · 28 SEP–4 OCT"). */
data class StatsWindow(val from: Long, val to: Long, val label: String)

/** The plays of one day, newest first, under "TODAY", "YESTERDAY" or "MON 5 OCT". */
data class DayGroup(val label: String, val plays: List<RecentPlay>)

/**
 * A week or month of listening, and the top artists and albums as they are in the library (null where it doesn't have
 * them: YouTube Music plays, say).
 */
data class StatsPage(val window: StatsWindow, val listening: Listening, val artists: List<Artist?>, val albums: List<Album?>)

/** What you played in a week or a month, from the history on the Tonearm server (1.7 or later). */
class ListeningStats(private val api: SubsonicApi, private val connect: PhoneConnect) {
    /** The [period] [offset] periods back (0 is this one, -1 the one before). */
    suspend fun load(period: Period, offset: Int, now: Long = System.currentTimeMillis()): StatsPage = coroutineScope {
        val window = window(period, offset, now, ZoneId.systemDefault(), Locale.getDefault())
        val library = async { runCatching { api.artists().flatMap { it.artist }.associateBy { Names.normalize(it.name) } }.getOrDefault(emptyMap()) }
        val listening = connect.listeningBetween(window.from, window.to, artists = TOP, albums = TOP, recent = PAGE)
        // Four at a time, like the other lookups of many names.
        val albums = listening.albums.chunked(4).flatMap { chunk -> chunk.map { async { libraryAlbum(it) } }.awaitAll() }
        val artists = library.await().let { byName -> listening.artists.map { byName[Names.normalize(it.artist)] } }
        StatsPage(window, listening, artists, albums)
    }

    /** The next page of plays in the same period: those before [before]. */
    suspend fun more(period: Period, offset: Int, before: Long, now: Long = System.currentTimeMillis()): List<RecentPlay> {
        val window = window(period, offset, now, ZoneId.systemDefault(), Locale.getDefault())
        return connect.listeningBetween(window.from, window.to, artists = 0, albums = 0, recent = PAGE, before = before).recent
    }

    private suspend fun libraryAlbum(count: AlbumCount): Album? = runCatching {
        val title = Names.normalize(SongMatch.cleanTitle(count.album))
        val artist = Names.normalize(count.artist)
        api.search(count.album, artistCount = 0, albumCount = 5, songCount = 0).album
            .firstOrNull { Names.normalize(SongMatch.cleanTitle(it.name)) == title && Names.normalize(it.artistLabel) == artist }
    }.getOrNull()

    companion object {
        const val TOP = 10
        /** Recent plays asked for at a time. */
        const val PAGE = 100

        /** The week (starting on [locale]'s first day of the week) or month [offset] periods from the one [now] is in, midnight to midnight in [zone]. */
        fun window(period: Period, offset: Int, now: Long, zone: ZoneId, locale: Locale): StatsWindow {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val start = when (period) {
                Period.WEEK -> today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek)).plusWeeks(offset.toLong())
                Period.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong())
            }
            val end = if (period == Period.WEEK) start.plusWeeks(1) else start.plusMonths(1)
            val name = when (period) {
                Period.WEEK -> days(start, end.minusDays(1), today)
                Period.MONTH -> format(start, if (start.year == today.year) "MMMM" else "MMMM yyyy")
            }
            val label = when (offset) {
                0 -> "THIS ${period.name} · $name"
                -1 -> "LAST ${period.name} · $name"
                else -> name
            }
            return StatsWindow(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli(), label)
        }

        /** [recent] (newest first) by the day they were played in [zone]. */
        fun dayGroups(recent: List<RecentPlay>, now: Long, zone: ZoneId): List<DayGroup> {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return recent.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.map { (day, plays) ->
                val label = when (day) {
                    today -> "TODAY"
                    today.minusDays(1) -> "YESTERDAY"
                    else -> format(day, if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy")
                }
                DayGroup(label, plays)
            }
        }

        /** "5–11 OCT", "28 SEP–4 OCT", "29 DEC 2025–4 JAN 2026". */
        private fun days(first: LocalDate, last: LocalDate, today: LocalDate): String {
            val year = if (last.year == today.year && first.year == today.year) "" else " yyyy"
            val from = when {
                first.year != last.year -> "d MMM yyyy"
                first.month != last.month -> "d MMM"
                else -> "d"
            }
            return format(first, from) + "–" + format(last, "d MMM$year")
        }

        private fun format(day: LocalDate, pattern: String) = day.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).uppercase(Locale.ROOT)
    }
}
