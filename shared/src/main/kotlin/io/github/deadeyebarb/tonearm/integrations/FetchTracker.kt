package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.likes.findSong
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Where a song that isn't (or wasn't) in the library stands. */
enum class Fetch { IN_LIBRARY, DOWNLOADING, REQUESTED }

data class FetchState(val fetch: Fetch, /** 0–1, while downloading. */ val progress: Float? = null)

/** What Lidarr is busy with: albums it's downloading and albums it's still looking for. */
data class LidarrActivity(val downloading: Map<String, Float> = emptyMap(), val wanted: Set<String> = emptySet()) {
    fun of(album: String?, artist: String?): FetchState? {
        val key = FetchTracker.albumKey(album ?: return null, artist ?: return null)
        downloading[key]?.let { return FetchState(Fetch.DOWNLOADING, it) }
        return if (key in wanted) FetchState(Fetch.REQUESTED) else null
    }
}

/**
 * Status icons for songs from outside the library (YouTube Music, waiting likes, playlist
 * placeholders): in the library already, downloading in Lidarr, or requested there. Lidarr's queue
 * and wanted list are fetched together now and then; library lookups are cached per song.
 */
class FetchTracker(private val lidarr: LidarrClient, private val api: SubsonicApi) {
    private val _activity = MutableStateFlow(LidarrActivity())
    val activity: StateFlow<LidarrActivity> = _activity.asStateFlow()
    private val library = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val gate = Semaphore(3)

    suspend fun refresh(config: LidarrConfig, key: String) {
        val queue = runCatching { lidarr.queue(config, key) }.getOrDefault(emptyList())
        val wanted = runCatching { lidarr.wanted(config, key) }.getOrDefault(emptyList())
        _activity.value = LidarrActivity(
            downloading = queue.mapNotNull { item ->
                val album = item.album?.title ?: return@mapNotNull null
                val artist = item.artist?.artistName ?: item.album.artist?.artistName ?: return@mapNotNull null
                albumKey(album, artist) to item.progress
            }.toMap(),
            wanted = wanted.mapNotNull { album -> album.artist?.artistName?.let { albumKey(album.title, it) } }.toSet(),
        )
    }

    /** Whether the library has [ref] (cached for a few minutes). */
    suspend fun inLibrary(ref: TrackRef, session: ServerSession): Boolean {
        val key = Names.key(SongMatch.primaryArtist(SongMatch.cleanArtist(ref.artist)), SongMatch.cleanTitle(ref.title))
        library[key]?.takeIf { System.currentTimeMillis() - it.second < LIBRARY_TTL }?.let { return it.first }
        val found = gate.withPermit { runCatching { api.findSong(ref, session) != null }.getOrDefault(false) }
        library[key] = found to System.currentTimeMillis()
        return found
    }

    /** The status of a song from outside the library: [album]/[artist] name what Lidarr would fetch. */
    suspend fun stateOf(ref: TrackRef, album: String?, artist: String?, session: ServerSession?): FetchState? {
        if (session != null && inLibrary(ref, session)) return FetchState(Fetch.IN_LIBRARY)
        return _activity.value.of(album ?: ref.album, artist ?: SongMatch.primaryArtist(SongMatch.cleanArtist(ref.artist)))
    }

    fun forget() = library.clear()

    companion object {
        private const val LIBRARY_TTL = 5 * 60_000L

        fun albumKey(album: String, artist: String) =
            Names.normalize(SongMatch.primaryArtist(SongMatch.cleanArtist(artist))) + "|" + Names.normalize(SongMatch.cleanTitle(album))
    }
}
