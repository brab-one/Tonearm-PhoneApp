package io.github.deadeyebarb.tonearm.integrations

import android.content.Context
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.jsonDataStore
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

@Serializable
private data class BrainarrState(
    /** A Brainarr list was found last time Lidarr was asked (shows the car's "Brainarr picks"). */
    val available: Boolean = false,
    /** MusicBrainz ids of artists added by runs started from Tonearm (found even without a tag). */
    val recorded: List<String> = emptyList(),
)

/**
 * Brainarr is a Lidarr import list that asks an AI model for music like your library and adds it to
 * Lidarr. Lidarr's API can't preview a list (and Brainarr's own review actions aren't reachable through
 * it), so its picks are read back from Lidarr: artists carrying the list's tag, plus whatever runs
 * started from here added.
 */
class BrainarrService(
    context: Context,
    json: Json,
    scope: CoroutineScope,
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val integrations: IntegrationsService,
) {
    private val store = jsonDataStore(context, "brainarr", BrainarrState.serializer(), BrainarrState(), json)
    private val lidarr get() = integrations.lidarr

    val available: StateFlow<Boolean> = store.data.map { it.available }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _data = MutableStateFlow<BrainarrData?>(null)
    /** The last loaded picks, shared by Discover's card and the Brainarr screen. */
    val data: StateFlow<BrainarrData?> = _data.asStateFlow()

    suspend fun lists(config: LidarrConfig, key: String): List<BrainarrList> =
        lidarr.importLists(config, key)
            .filter { (list, _) -> list.implementation.equals(BrainarrList.IMPLEMENTATION, true) }
            .map { (list, raw) -> BrainarrList.from(list, raw) }

    suspend fun load(): BrainarrData = coroutineScope {
        val (config, key) = integrations.requireLidarr()
        val lists = lists(config, key)
        if (lists.isEmpty()) {
            store.updateData { it.copy(available = false) }
            return@coroutineScope BrainarrData(emptyList(), emptyList()).also { _data.value = it }
        }
        val artists = async { lidarr.artists(config, key) }
        val queue = async { runCatching { lidarr.queue(config, key) }.getOrDefault(emptyList()) }
        val library = async { runCatching { api.artists().flatMap { it.artist } }.getOrDefault(emptyList()) }
        val state = store.updateData { it.copy(available = true) }

        val picks = BrainarrPicks.pickArtists(artists.await(), lists.flatMap { it.tags }.toSet(), state.recorded.toSet())
        val byName = library.await().associateBy { Recommender.normalize(it.name) }
        val downloads = queue.await().groupBy { it.artistId ?: it.artist?.id }
        BrainarrData(
            lists = lists,
            picks = picks.map { artist ->
                val downloading = downloads[artist.id]
                val inLibrary = byName[Recommender.normalize(artist.artistName)]
                val onDisk = artist.statistics?.trackFileCount ?: 0
                val wanted = artist.statistics?.trackCount ?: 0
                BrainarrPick(
                    lidarrId = artist.id,
                    name = artist.artistName,
                    genres = artist.genres,
                    added = artist.added?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    status = BrainarrPicks.status(artist, inLibrary != null, downloading != null),
                    tracksOnDisk = onDisk,
                    tracksWanted = wanted,
                    progress = downloading?.let { items -> items.map { it.progress }.average().toFloat() },
                    imageUrl = LidarrClient.posterUrl(config, artist),
                    libraryArtist = inLibrary,
                )
            },
        ).also { _data.value = it }
    }

    private val _asking = MutableStateFlow<String?>(null)
    /** Lidarr's status text while [ask] runs, else null. */
    val asking: StateFlow<String?> = _asking.asStateFlow()

    /**
     * Runs every Brainarr list now (Lidarr's "ImportListSync" for that list), waits for the model and
     * Lidarr to finish, and returns the artists that appeared. One run at a time.
     */
    suspend fun ask(): AskResult {
        if (!_asking.compareAndSet(null, "Asking Brainarr…")) throw IllegalStateException("Brainarr is already working on it")
        try {
            return runAsk().also { runCatching { load() } }
        } finally {
            _asking.value = null
        }
    }

    private suspend fun runAsk(): AskResult {
        val (config, key) = integrations.requireLidarr()
        val lists = lists(config, key).ifEmpty { throw IntegrationNotConfiguredException("Lidarr has no Brainarr import list") }
        val before = lidarr.artists(config, key).mapNotNull { it.foreignArtistId }.toSet()
        var message: String? = null
        for (list in lists) {
            var command = lidarr.startCommand(config, key, "ImportListSync", "definitionId" to list.id)
            val deadline = System.currentTimeMillis() + ASK_TIMEOUT_MS
            while (!command.finished && System.currentTimeMillis() < deadline) {
                command.message?.let { _asking.value = it }
                delay(POLL_MS)
                command = lidarr.command(config, key, command.id)
            }
            when {
                !command.finished -> throw IntegrationHttpException(0, "Lidarr: Brainarr is still working; its picks will show up here when it's done")
                command.status != "completed" -> throw IntegrationHttpException(0, "Lidarr: " + (command.message ?: "the Brainarr run ${command.status}"))
            }
            message = command.message
        }
        val added = lidarr.artists(config, key).filter { it.foreignArtistId != null && it.foreignArtistId !in before }
        store.updateData { state -> state.copy(recorded = (state.recorded + added.mapNotNull { it.foreignArtistId }).distinct()) }
        return AskResult(added.map { it.artistName }, message)
    }

    /** Tags every Brainarr list with "brainarr", so Lidarr labels what it adds from now on. */
    suspend fun label() {
        val (config, key) = integrations.requireLidarr()
        val tag = lidarr.tags(config, key).firstOrNull { it.label.equals(TAG, true) } ?: lidarr.createTag(config, key, TAG)
        for (list in lists(config, key)) {
            if (tag.id in list.tags) continue
            val tags = JsonArray((list.tags + tag.id).map { JsonPrimitive(it) })
            lidarr.updateImportList(config, key, list.id, JsonObject(list.raw + ("tags" to tags)))
        }
    }

    /** Monitors a pick Brainarr added unmonitored, with the albums a request would get, and searches for it. */
    suspend fun getIt(pick: BrainarrPick) {
        val (config, key) = integrations.requireLidarr()
        val monitor = config.monitor.takeIf { it != "none" } ?: "all"
        lidarr.monitorArtists(config, key, listOf(pick.lidarrId), monitor, search = config.searchOnAdd)
    }

    /** Picks that are in the music library, without asking Lidarr when no Brainarr list is known. */
    suspend fun libraryPicks(): List<Artist> {
        if (!available.value || integrations.state.value.lidarr == null) return emptyList()
        return load().inLibrary.mapNotNull { it.libraryArtist }
    }

    /** "Brainarr mix": songs of the picks you have, a few per artist, newest picks first. */
    suspend fun mix(size: Int = 60): List<QueueSong> = coroutineScope {
        val session = sessions.awaitActive()
        val artists = libraryPicks().take(15)
        val perArtist = (size / artists.size.coerceAtLeast(1)).coerceIn(3, 8)
        val buckets = artists.map { artist ->
            async { runCatching { songsOf(session, artist.id).shuffled().take(perArtist) }.getOrDefault(emptyList()) }
        }.awaitAll()
        DailyDiscovery.interleave(buckets).take(size).map { QueueSong(session.id, it) }
    }

    private suspend fun songsOf(session: ServerSession, artistId: String): List<Song> = coroutineScope {
        api.artist(artistId, session).album.map { album -> async { api.album(album.id, session).song } }.awaitAll().flatten()
    }

    companion object {
        const val TAG = "brainarr"
        private const val POLL_MS = 2_000L
        /** Local models can take a while; Lidarr keeps running the list after this anyway. */
        private const val ASK_TIMEOUT_MS = 15 * 60_000L
    }
}
