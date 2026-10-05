package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.ArtistInfo
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

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
    /** Artists you've scrobbled most that the library doesn't have. */
    val historyNotInLibrary: List<MissingArtist>,
    /** False when the server returned no similar-artist data at all (no Last.fm/Deezer agent). */
    val hasSimilarData: Boolean,
    val libraryEmpty: Boolean,
)

/**
 * Recommendations from listening history in Maloja, matched against the library:
 * what's in rotation, favourites that dropped out of it, and artists similar to what you play
 * (similarity comes from the Subsonic server's metadata agents, e.g. Last.fm in Navidrome).
 */
class Recommender(
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val integrations: IntegrationsService,
) {
    private val maloja get() = integrations.maloja

    suspend fun discover(): DiscoverData = coroutineScope {
        val config = integrations.requireMaloja()
        val today = LocalDate.now()
        val recent = async { maloja.topArtists(config, today.minusDays(30), 25) }
        val quarter = async { maloja.topArtists(config, today.minusDays(90), 500) }
        val allTime = async { maloja.topArtists(config, null, 80) }
        val count = async { runCatching { maloja.scrobbleCount(config, today.minusDays(30)) }.getOrDefault(0) }
        val library = async { api.artists().flatMap { it.artist } }

        val byName = library.await().associateBy { normalize(it.name) }
        val recentArtists = recent.await().filter { !it.artist.isNullOrBlank() }
        val playedLately = quarter.await().mapNotNull { it.artist?.let(::normalize) }.toSet()

        val rotation = recentArtists.take(12).map { RotationEntry(it.artist!!, it.scrobbles, byName[normalize(it.artist)]) }

        val rediscover = allTime.await()
            .filter { !it.artist.isNullOrBlank() && normalize(it.artist) !in playedLately }
            .mapNotNull { entry -> byName[normalize(entry.artist!!)]?.let { LibraryPick(it, "${entry.scrobbles} plays, none in 3 months") } }
            .distinctBy { it.artist.id }
            .take(12)

        // Similar artists of what you play most, split into owned-but-unplayed and not owned.
        val seedEntries = recentArtists.ifEmpty { allTime.await() }
        val seeds = seedEntries.mapNotNull { e -> e.artist?.let { byName[normalize(it)] } }.distinctBy { it.id }.take(8)
        val infos = seeds.map { seed -> async { seed to api.artistInfo(seed.id, includeNotPresent = true, count = 20) } }.awaitAll()
        val similar = splitSimilar(infos, byName, playedLately, skip = (seeds.map { it.name } + rediscover.map { it.artist.name }).map(::normalize).toSet())

        val fromHistory = (recentArtists + allTime.await())
            .filter { !it.artist.isNullOrBlank() && normalize(it.artist) !in byName }
            .distinctBy { normalize(it.artist!!) }
            .sortedByDescending { it.scrobbles }
            .take(if (byName.isEmpty()) 40 else 15)
            .map { MissingArtist(it.artist!!, "${it.scrobbles} plays in your history", null) }

        // Every list is keyed by name or id on screen, and a repeated key crashes Compose: keep them unique.
        DiscoverData(
            scrobblesLast30Days = count.await(),
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
     * "Maloja mix": songs similar to your most-played tracks of the last month, leaving out
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
        val config = integrations.requireMaloja()
        val top = async { maloja.topTracks(config, LocalDate.now().minusDays(30), 12) }
        val heard = async { runCatching { maloja.recentScrobbles(config, 200) }.getOrDefault(emptyList()) }
        val seeds = top.await().mapNotNull { it.track }.take(8)
            .map { track -> async { findInLibrary(session, track) } }.awaitAll().filterNotNull()
        val similar = seeds.map { seed -> async { runCatching { api.similarSongs(seed.id, 20) }.getOrDefault(emptyList()) } }.awaitAll()
        SimilarPool(
            songs = (similar.flatten() + seeds).distinctBy { it.id },
            recentlyHeard = heard.await().mapNotNull { it.track }.map { key(it.artists.firstOrNull().orEmpty(), it.title) }.toSet(),
        )
    }

    private suspend fun findInLibrary(session: ServerSession, track: MalojaTrack): Song? {
        val artist = track.artists.firstOrNull() ?: return null
        val results = runCatching { api.search(track.title, artistCount = 0, albumCount = 0, songCount = 15, session = session).song }
            .getOrDefault(emptyList())
        return results.firstOrNull { normalize(it.title) == normalize(track.title) && normalize(it.artist.orEmpty()).contains(normalize(artist)) }
            ?: results.firstOrNull { normalize(it.title) == normalize(track.title) }
    }

    /** Similar artists of the seeds: ones in the library you haven't played lately, and ones you don't have. */
    data class SimilarSplit(val owned: List<LibraryPick>, val missing: List<MissingArtist>, val any: Boolean)

    companion object {
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
