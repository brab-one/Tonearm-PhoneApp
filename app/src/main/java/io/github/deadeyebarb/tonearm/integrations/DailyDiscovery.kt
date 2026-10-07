package io.github.deadeyebarb.tonearm.integrations

import android.content.Context
import io.github.deadeyebarb.tonearm.data.jsonDataStore
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.random.Random

/** A recommended artist that isn't in the library, as listed in the daily mix. */
@Serializable
data class DailyMissing(val name: String, val reason: String, val imageUrl: String? = null)

@Serializable
data class DailyMix(
    val date: String,
    val serverId: String,
    val songs: List<Song> = emptyList(),
    val missing: List<DailyMissing> = emptyList(),
    /** The "Daily Discovery" playlist on the server, kept in sync with [songs]. */
    val playlistId: String? = null,
    /** Normalized names already sent to Lidarr. */
    val requested: List<String> = emptyList(),
) {
    val entries: List<QueueSong> get() = songs.map { QueueSong(serverId, it) }
    val toRequest: List<DailyMissing> get() = missing.filter { Recommender.normalize(it.name) !in requested }
}

@Serializable
private data class DailyState(val mix: DailyMix? = null)

data class RequestSummary(val requested: Int, val alreadyThere: Int, val notFound: Int, val failed: Int)

/**
 * "Daily Discovery": once a day, a fresh mix of library songs picked from your listening history
 * (forgotten favourites, similar artists you rarely play, songs like your current favourites),
 * plus the recommended artists you don't have yet, which can all be requested from Lidarr.
 * The songs are also kept as a "Daily Discovery" playlist on the music server.
 */
class DailyDiscovery(
    context: Context,
    json: Json,
    scope: CoroutineScope,
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val integrations: IntegrationsService,
    private val recommender: Recommender,
) {
    private val store = jsonDataStore(context, "daily", DailyState.serializer(), DailyState(), json)
    private val mutex = Mutex()

    val state: StateFlow<DailyMix?> = store.data.map { it.mix }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Today's mix for the active server, generating it on the first call of the day. */
    suspend fun ensureToday(force: Boolean = false): DailyMix = mutex.withLock {
        val session = sessions.awaitActive()
        val today = LocalDate.now().toString()
        val stored = store.data.first().mix
        // A mix built while the library was empty is retried, so music Lidarr fetched later today shows up.
        val fresh = stored != null && stored.date == today && stored.serverId == session.id && stored.songs.isNotEmpty()
        if (!force && fresh) return stored!!
        val mix = generate(session, today, previous = stored?.takeIf { it.serverId == session.id })
        store.updateData { DailyState(mix) }
        mix
    }

    private suspend fun generate(session: ServerSession, today: String, previous: DailyMix?): DailyMix = coroutineScope {
        // Seeded by the date, so a regeneration on the same day gives the same mix.
        val random = Random(LocalDate.parse(today).toEpochDay() * 31 + session.id.hashCode())
        val discover = recommender.discover()
        val pool = async { runCatching { recommender.similarToFavourites(session) }.getOrNull() }

        val artistIds = (discover.rediscover.take(4) + discover.similarInLibrary.take(6)).map { it.artist.id }
        val artistBuckets = artistIds.distinct()
            .map { id -> async { runCatching { songsOf(session, id, 3, random) }.getOrDefault(emptyList()) } }
        val similar = pool.await()
        val recentlyHeard = similar?.recentlyHeard.orEmpty()
        val buckets = artistBuckets.awaitAll() + listOf(similar?.songs.orEmpty().shuffled(random).take(12))

        val songs = interleave(buckets)
            .filter { Recommender.key(it.artist.orEmpty(), it.title) !in recentlyHeard }
            .distinctBy { it.id }
            .take(SIZE)
            .toMutableList()
        if (songs.size < SIZE / 2 && !discover.libraryEmpty) {
            songs += runCatching { api.randomSongs(SIZE - songs.size, session) }.getOrDefault(emptyList())
                .filter { s -> songs.none { it.id == s.id } }
        }

        val missing = (
            discover.rotation.filter { it.artist == null }.map { DailyMissing(it.name, "In your rotation: ${it.scrobbles} plays") } +
                discover.notInLibrary.map { DailyMissing(it.name, it.reason, it.imageUrl) } +
                discover.historyNotInLibrary.map { DailyMissing(it.name, it.reason, it.imageUrl) }
            ).distinctBy { Recommender.normalize(it.name) }.take(12)

        val playlistId = songs.takeIf { it.isNotEmpty() }
            ?.let { runCatching { syncPlaylist(session, it, previous?.playlistId, today) }.getOrNull() } ?: previous?.playlistId
        DailyMix(
            date = today,
            serverId = session.id,
            songs = songs,
            missing = missing,
            playlistId = playlistId,
            requested = previous?.requested.orEmpty(),
        )
    }

    /** A few songs by an artist, from a random selection of their albums. */
    private suspend fun songsOf(session: ServerSession, artistId: String, count: Int, random: Random): List<Song> {
        val albums = api.artist(artistId, session).album.shuffled(random).take(2)
        return albums.flatMap { api.album(it.id, session).song }.shuffled(random).take(count)
    }

    /** Creates or refreshes the server playlist. Returns its id. */
    private suspend fun syncPlaylist(session: ServerSession, songs: List<Song>, existingId: String?, today: String): String? {
        val ids = songs.map { it.id }
        val id = existingId?.takeIf { id -> runCatching { api.playlist(id, session) }.isSuccess }?.also { api.replacePlaylist(it, ids, session) }
            ?: api.createPlaylist(PLAYLIST_NAME, ids, session)?.id
            ?: api.playlists(session).firstOrNull { it.name == PLAYLIST_NAME }?.id
        id?.let { runCatching { api.setPlaylistComment(it, "Tonearm: recommendations for $today, from what you play", session) } }
        return id
    }

    /**
     * Sends every not-yet-requested artist of today's mix to Lidarr. [monitor] decides how much
     * gets downloaded per artist ("latest" is a sensible default for a batch).
     */
    suspend fun requestAll(monitor: String): RequestSummary {
        val mix = state.value ?: ensureToday()
        var requested = 0
        var already = 0
        var notFound = 0
        var failed = 0
        val done = mutableListOf<String>()
        for (artist in mix.toRequest) {
            try {
                when (integrations.requestArtistByName(artist.name, monitor)) {
                    IntegrationsService.ArtistRequestResult.REQUESTED -> requested++
                    IntegrationsService.ArtistRequestResult.ALREADY_IN_LIDARR -> already++
                    IntegrationsService.ArtistRequestResult.NOT_FOUND -> notFound++
                }
                done += Recommender.normalize(artist.name)
            } catch (e: IntegrationNotConfiguredException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }
        markRequested(done)
        return RequestSummary(requested, already, notFound, failed)
    }

    suspend fun markRequested(normalizedNames: Collection<String>) {
        if (normalizedNames.isEmpty()) return
        store.updateData { state -> state.copy(mix = state.mix?.let { it.copy(requested = (it.requested + normalizedNames).distinct()) }) }
    }

    companion object {
        const val PLAYLIST_NAME = "Daily Discovery"
        private const val SIZE = 30

        /** Round-robin across sources so the mix alternates instead of playing one artist in a row. */
        fun <T> interleave(buckets: List<List<T>>): List<T> {
            val out = ArrayList<T>()
            val max = buckets.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (bucket in buckets) bucket.getOrNull(i)?.let(out::add)
            return out
        }
    }
}
