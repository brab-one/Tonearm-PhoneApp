package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.ArtistCount
import io.github.deadeyebarb.tonearm.connect.PhoneConnect
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.ArtistInfo
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

data class RotationEntry(val name: String, val scrobbles: Int, val artist: Artist?)

/** An artist in the library worth playing, and why. */
data class LibraryPick(val artist: Artist, val reason: String)

/** A recommended artist that isn't in the library (a candidate for a Lidarr request). */
data class MissingArtist(val name: String, val reason: String, val imageUrl: String?)

data class DiscoverData(
    val scrobblesLast30Days: Int,
    val rotation: List<RotationEntry>,
    val rediscover: List<LibraryPick>,
    val similarInLibrary: List<LibraryPick>,
    val notInLibrary: List<MissingArtist>,
    /** Artists you've played most that the library doesn't have (heard on YouTube Music). */
    val historyNotInLibrary: List<MissingArtist>,
    /** False when the server returned no similar-artist data at all (no Last.fm/Deezer agent). */
    val hasSimilarData: Boolean,
    val libraryEmpty: Boolean,
)

/**
 * Recommendations from the listening history on the Tonearm server (every app's plays, YouTube Music ones
 * too) and Navidrome's own play counts, matched against the library: what's in rotation, favourites that
 * dropped out of it, and artists similar to what you play (from the music server's metadata agents).
 */
class Recommender(
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val connect: PhoneConnect,
) {
    suspend fun discover(): DiscoverData = coroutineScope {
        val month = async { connect.listening(30, artists = 25) }
        val quarter = async { connect.listening(90, artists = 500) }
        val allTime = async { connect.listening(null, artists = 80) }
        // Navidrome counts library plays from before the history started, and from other apps.
        val frequent = async { runCatching { api.albumList(AlbumListType.FREQUENT, 500) }.getOrDefault(emptyList()) }
        val library = async { api.artists().flatMap { it.artist } }

        val byName = library.await().associateBy { normalize(it.name) }
        val recentArtists = month.await().artists
        val quarterAgo = System.currentTimeMillis() - 90 * DAY_MS
        val counted = frequent.await().filter { it.artistLabel.isNotBlank() }.groupBy { normalize(it.artistLabel) }
        val playedLately = quarter.await().artists.map { normalize(it.artist) }.toSet() +
            counted.filterValues { albums -> albums.any { (it.played?.let(::millis) ?: 0) > quarterAgo } }.keys
        val everPlayed = (allTime.await().artists + counted.values.map { albums -> ArtistCount(albums.first().artistLabel, albums.sumOf { it.playCount }.toInt()) })
            .groupBy { normalize(it.artist) }.map { (_, both) -> both.maxBy { it.plays } }
            .filter { it.plays > 0 }.sortedByDescending { it.plays }.take(80)

        val rotation = recentArtists.take(12).map { RotationEntry(it.artist, it.plays, byName[normalize(it.artist)]) }

        val rediscover = everPlayed
            .filter { normalize(it.artist) !in playedLately }
            .mapNotNull { entry -> byName[normalize(entry.artist)]?.let { LibraryPick(it, "${entry.plays} plays, none in 3 months") } }
            .distinctBy { it.artist.id }
            .take(12)

        // Similar artists of what you play most, split into owned-but-unplayed and not owned.
        val seedEntries = recentArtists.ifEmpty { everPlayed }
        val seeds = seedEntries.mapNotNull { byName[normalize(it.artist)] }.distinctBy { it.id }.take(8)
        val infos = seeds.map { seed -> async { seed to api.artistInfo(seed.id, includeNotPresent = true, count = 20) } }.awaitAll()
        val similar = splitSimilar(infos, byName, playedLately, skip = (seeds.map { it.name } + rediscover.map { it.artist.name }).map(::normalize).toSet())

        val fromHistory = (recentArtists + everPlayed)
            .filter { normalize(it.artist) !in byName }
            .distinctBy { normalize(it.artist) }
            .sortedByDescending { it.plays }
            .take(if (byName.isEmpty()) 40 else 15)
            .map { MissingArtist(it.artist, "${it.plays} plays in your history", null) }

        // Every list is keyed by name or id on screen, and a repeated key crashes Compose: keep them unique.
        DiscoverData(
            scrobblesLast30Days = month.await().plays,
            rotation = rotation.distinctBy { it.name },
            rediscover = rediscover,
            similarInLibrary = similar.owned,
            notInLibrary = similar.missing,
            historyNotInLibrary = fromHistory.distinctBy { it.name },
            hasSimilarData = similar.any,
            libraryEmpty = byName.isEmpty(),
        )
    }

    /**
     * "Your mix": songs similar to your most-played tracks of the last month, leaving out
     * anything you played recently. Falls back to random songs when there's little to go on.
     */
    suspend fun mix(size: Int = 60): List<QueueSong> {
        val session = sessions.awaitActive()
        val pool = similarToFavourites(session)
        val picks = pool.songs.filter { key(it.artist.orEmpty(), it.title) !in pool.recentlyHeard }.shuffled().take(size).toMutableList()
        if (picks.size < 15) picks += api.randomSongs(size - picks.size, session).filter { s -> picks.none { it.id == s.id } }
        return picks.shuffled().map { QueueSong(session.id, it) }
    }

    /** Songs similar to your most-played tracks of the last month, plus what you played recently (to skip). */
    class SimilarPool(val songs: List<Song>, val recentlyHeard: Set<String>)

    suspend fun similarToFavourites(session: ServerSession): SimilarPool = coroutineScope {
        val listening = connect.listening(30, artists = 0, songs = 12, recent = 200)
        val seeds = listening.songs.take(8)
            .map { song -> async { findInLibrary(session, song.artist, song.title) } }.awaitAll().filterNotNull()
        val similar = seeds.map { seed -> async { runCatching { api.similarSongs(seed.id, 20) }.getOrDefault(emptyList()) } }.awaitAll()
        SimilarPool(
            songs = (similar.flatten() + seeds).distinctBy { it.id },
            recentlyHeard = listening.recent.map { key(it.artist, it.title) }.toSet(),
        )
    }

    private suspend fun findInLibrary(session: ServerSession, artist: String, title: String): Song? {
        val results = runCatching { api.search(title, artistCount = 0, albumCount = 0, songCount = 15, session = session).song }
            .getOrDefault(emptyList())
        return results.firstOrNull { normalize(it.title) == normalize(title) && normalize(it.artist.orEmpty()).contains(normalize(artist)) }
            ?: results.firstOrNull { normalize(it.title) == normalize(title) }
    }

    /** Similar artists of the seeds: ones in the library you haven't played lately, and ones you don't have. */
    data class SimilarSplit(val owned: List<LibraryPick>, val missing: List<MissingArtist>, val any: Boolean)

    companion object {
        private const val DAY_MS = 24 * 3_600_000L

        private fun millis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

        /**
         * Sorts the seeds' similar artists (from getArtistInfo2 with includeNotPresent) into library picks
         * and missing artists, most-shared first. Names in [skip] (the seeds, rediscover picks) are left out.
         */
        fun splitSimilar(
            infos: List<Pair<Artist, ArtistInfo?>>,
            byName: Map<String, Artist>,
            playedLately: Set<String>,
            skip: Set<String>,
        ): SimilarSplit {
            val owned = linkedMapOf<String, Pair<Artist, MutableList<String>>>()
            val missing = linkedMapOf<String, Triple<String, MutableList<String>, String?>>()
            var any = false
            for ((seed, info) in infos) {
                for (similar in info?.similarArtist.orEmpty()) {
                    any = true
                    val key = normalize(similar.name)
                    if (key.isBlank() || key in skip) continue
                    val inLibrary = byName[key] ?: similar.takeIf { it.inLibrary }
                    if (inLibrary != null) {
                        if (key !in playedLately) owned.getOrPut(key) { inLibrary to mutableListOf() }.second += seed.name
                    } else {
                        missing.getOrPut(key) { Triple(similar.name, mutableListOf(), similar.artistImageUrl) }.second += seed.name
                    }
                }
            }
            return SimilarSplit(
                owned = owned.values.sortedByDescending { it.second.size }
                    .distinctBy { it.first.id }
                    .take(15)
                    .map { (artist, because) -> LibraryPick(artist, "Because you play ${because.distinct().take(2).joinToString(" & ")}") },
                missing = missing.values.sortedByDescending { it.second.size }
                    .take(20)
                    .map { (name, because, image) -> MissingArtist(name, "Like ${because.distinct().take(2).joinToString(" & ")}", image) },
                any = any,
            )
        }

        fun normalize(name: String): String = Names.normalize(name)

        fun key(artist: String, title: String) = Names.key(artist, title)
    }
}
