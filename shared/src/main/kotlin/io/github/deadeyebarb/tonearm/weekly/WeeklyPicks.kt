package io.github.deadeyebarb.tonearm.weekly

import io.github.deadeyebarb.tonearm.connect.AiPick
import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRoute
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An album a weekly run added in Lidarr. */
@Serializable
data class WeeklyAlbum(
    val lidarrId: Int,
    val title: String,
    val artist: String,
    val artistId: Int,
    /** The run added the artist too (so it can go again with the album). */
    val newArtist: Boolean,
)

/** What a weekly playlist's comment carries after [WeeklyPicks.MARKER]. */
@Serializable
data class WeeklyState(
    val created: Long,
    /** The device that started the run; only it finishes a [RUNNING] one. */
    val by: String,
    val status: String = RUNNING,
    val albums: List<WeeklyAlbum> = emptyList(),
    val message: String? = null,
) {
    companion object {
        const val RUNNING = "running"
        const val READY = "ready"
    }
}

data class WeeklyBatch(val playlist: Playlist, val state: WeeklyState) {
    val expires: Long get() = state.created + WeeklyPicks.WEEK
}

/** Whether weekly picks are on, and how many albums a week; kept on the Tonearm server for all the user's devices. */
@Serializable
data class WeeklySettings(val on: Boolean = false, val albums: Int = WeeklyPicks.DEFAULT_ALBUMS)

/** The run a device started and is waiting for: fresh AI picks asked for at [started]. */
@Serializable
private data class RunInProgress(val playlistId: String, val started: Long, val albums: Int = WeeklyPicks.DEFAULT_ALBUMS)

/**
 * Weekly picks: once a week the Tonearm server's AI suggests albums from what you play, Lidarr downloads
 * the first few, and a playlist on the music server collects them. A week later, when the next selection
 * arrives, the old playlist and its music are deleted again, except albums with a song you liked or put in
 * another playlist. Liking the playlist itself (and naming it) keeps it all.
 *
 * The weekly playlist's comment holds the state, so the phone and the desktop share it. [runFile] is this
 * device's note of a run it started.
 */
class WeeklyPicks(
    private val api: SubsonicApi,
    private val lidarr: LidarrClient,
    private val connect: ConnectClient,
    private val json: Json,
    private val deviceId: String,
    private val runFile: File,
) {
    suspend fun settings(session: ServerSession): WeeklySettings =
        connect.storeGet(ConnectRoute.Server(session), SETTINGS_KEY).value
            ?.let { runCatching { json.decodeFromString(WeeklySettings.serializer(), it) }.getOrNull() } ?: WeeklySettings()

    suspend fun saveSettings(session: ServerSession, settings: WeeklySettings) {
        val route = ConnectRoute.Server(session)
        repeat(4) {
            val stored = connect.storeGet(route, SETTINGS_KEY)
            if (connect.storePut(route, SETTINGS_KEY, json.encodeToString(WeeklySettings.serializer(), settings), stored.version) != null) return
        }
        throw IOException("Couldn't save the weekly picks setting; try again")
    }

    suspend fun batches(session: ServerSession): List<WeeklyBatch> =
        api.playlists(session).mapNotNull { playlist -> parse(playlist, json)?.let { WeeklyBatch(playlist, it) } }

    /**
     * Does what's due: finishes this device's run, starts this week's when the newest is a week old,
     * deletes the weeks before it, and fills the playlists with what has downloaded. Returns news for
     * the user, if any.
     */
    suspend fun tick(config: LidarrConfig, key: String, session: ServerSession, now: Long = System.currentTimeMillis()): String? {
        runFile.parentFile?.mkdirs()
        val settings = moveFromBrainarr(config, key, session) ?: settings(session)
        var news: String? = finishRun(config, key, session, now)
        var batches = batches(session)
        // A run whose device went away doesn't block the next week forever.
        batches.filter { it.state.status == WeeklyState.RUNNING && now - it.state.created > ABANDONED }.forEach {
            api.deletePlaylist(it.playlist.id, session)
        }
        batches = batches.filterNot { it.state.status == WeeklyState.RUNNING && now - it.state.created > ABANDONED }
        val newest = batches.maxByOrNull { it.state.created }
        val lastAttempt = attemptFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val due = newest == null || now - newest.state.created >= WEEK - SLACK
        if (settings.on && due && !runFile.exists() && now - lastAttempt >= RETRY) {
            attemptFile.writeText(now.toString())
            news = startRun(session, settings, now) ?: news
            batches = batches(session)
        }
        val current = batches.maxByOrNull { it.state.created }
        val gone = mutableSetOf<String>()
        for (old in batches) {
            val replaced = old !== current && current != null && old.state.created < current.state.created && current.state.status == WeeklyState.READY
            val lapsed = !settings.on && now >= old.expires
            if (old.state.status == WeeklyState.READY && (replaced || lapsed)) {
                cleanUp(config, key, session, old)
                gone += old.playlist.id
            }
        }
        batches.filter { it.state.status == WeeklyState.READY && it.playlist.id !in gone }.forEach { runCatching { fill(session, it) } }
        return news
    }

    /** Keeps a weekly playlist and its music for good, under [name]. */
    suspend fun keep(session: ServerSession, batch: WeeklyBatch, name: String) {
        api.renamePlaylist(batch.playlist.id, name.trim(), session)
        api.setPlaylistComment(batch.playlist.id, "Kept from the weekly picks of ${date(batch.state.created)}", session)
    }

    /**
     * Weekly picks used to run on a Brainarr list in Lidarr that Tonearm made (as did "more like this"):
     * takes over its on/off and size, and removes Tonearm's lists. Returns the settings when it did.
     */
    private suspend fun moveFromBrainarr(config: LidarrConfig, key: String, session: ServerSession): WeeklySettings? {
        if (movedFile.exists()) return null
        val ours = lidarr.importLists(config, key).filter { (list, _) -> list.implementation == "Brainarr" && list.name.startsWith(OLD_LIST_PREFIX) }
        val weekly = ours.firstOrNull { (list, _) -> list.name == OLD_WEEKLY_LIST }?.first
        val settings = weekly?.let { WeeklySettings(on = true, albums = it.field("maxRecommendations")?.toIntOrNull() ?: DEFAULT_ALBUMS) }
        if (settings != null) saveSettings(session, settings)
        ours.forEach { (list, _) -> lidarr.deleteImportList(config, key, list.id) }
        movedFile.writeText(System.currentTimeMillis().toString())
        return settings
    }

    private suspend fun startRun(session: ServerSession, settings: WeeklySettings, now: Long): String? {
        val state = WeeklyState(created = now, by = deviceId)
        val playlist = api.createPlaylist(playlistName(now), emptyList(), session)
            ?: api.playlists(session).firstOrNull { it.name == playlistName(now) && parse(it, json) == null }
            ?: throw IOException("The music server didn't create the weekly playlist")
        api.setPlaylistComment(playlist.id, comment(state, json), session)
        // Phone and desktop may start at the same moment: the earlier claim wins.
        val rivals = batches(session).filter { it.playlist.id != playlist.id && now - it.state.created < WEEK - SLACK }
        if (rivals.any { it.state.created < now || (it.state.created == now && it.playlist.id < playlist.id) }) {
            api.deletePlaylist(playlist.id, session)
            return null
        }
        try {
            connect.aiPicks(session, refresh = true)
            writeRun(RunInProgress(playlist.id, now, settings.albums))
        } catch (e: Exception) {
            api.deletePlaylist(playlist.id, session)
            throw e
        }
        return "Picking this week's albums from what you play"
    }

    private suspend fun finishRun(config: LidarrConfig, key: String, session: ServerSession, now: Long): String? {
        val run = readRun() ?: return null
        val picks = runCatching { connect.aiPicks(session) }.getOrNull()
        val fresh = picks != null && !picks.running && picks.madeAt >= run.started
        val failed = picks != null && !picks.running && picks.madeAt < run.started && picks.problem != null
        if (!fresh && !failed && now - run.started < ABANDONED) return null
        runFile.delete()
        val playlist = runCatching { api.playlist(run.playlistId, session) }.getOrNull() ?: return null
        val state = parse(playlist, json) ?: return null
        if (!fresh) {
            api.deletePlaylist(playlist.id, session)
            return "This week's picks didn't work: ${picks?.problem ?: "the AI took too long"}"
        }
        val added = request(config, key, picks.picks, run.albums)
        if (added.isEmpty()) {
            api.deletePlaylist(playlist.id, session)
            return "Nothing new to get from the AI picks this week"
        }
        api.setPlaylistComment(playlist.id, comment(state.copy(status = WeeklyState.READY, albums = added), json), session)
        return "This week's picks: ${added.size} albums are downloading"
    }

    /** Has Lidarr get the first [count] picks it knows and doesn't already want. */
    private suspend fun request(config: LidarrConfig, key: String, picks: List<AiPick>, count: Int): List<WeeklyAlbum> {
        val artistsBefore = lidarr.artists(config, key).map { it.id }.toSet()
        val defaults = lidarr.resolveDefaults(config, key).copy(search = true)
        val added = mutableListOf<WeeklyAlbum>()
        for (pick in picks) {
            if (added.size >= count) break
            val hit = runCatching { lidarr.lookupAlbum(config, key, "${pick.artist} ${pick.album}") }.getOrDefault(emptyList()).firstOrNull {
                Names.normalize(SongMatch.cleanTitle(it.title)) == Names.normalize(SongMatch.cleanTitle(pick.album)) &&
                    Names.normalize(it.artistName.orEmpty()) == Names.normalize(pick.artist)
            } ?: continue
            // Already wanted: not this week's to delete later.
            if (hit.inLidarr && hit.monitored) continue
            added += runCatching {
                if (hit.inLidarr) {
                    lidarr.monitorAlbum(config, key, hit.lidarrId, search = true)
                    WeeklyAlbum(hit.lidarrId, hit.title, hit.artistName ?: pick.artist, hit.artistId, newArtist = false)
                } else {
                    val album = lidarr.addAlbum(config, key, hit.candidate, defaults)
                    WeeklyAlbum(album.id, album.title, album.artist?.artistName ?: pick.artist, album.artistId, newArtist = album.artistId !in artistsBefore)
                }
            }.getOrNull() ?: continue
        }
        return added
    }

    /** Puts the downloaded songs of the batch's albums into its playlist, in album order. */
    private suspend fun fill(session: ServerSession, batch: WeeklyBatch) {
        val songs = batch.state.albums.flatMap { libraryAlbum(session, it) }
        val ids = songs.map { it.id }
        val current = runCatching { api.playlist(batch.playlist.id, session).entry.map { it.id } }.getOrDefault(emptyList())
        if (ids.isEmpty() || ids == current) return
        api.replacePlaylist(batch.playlist.id, ids, session)
        // Some servers reset the comment with the songs; it holds the state, so put it back.
        if (runCatching { api.playlist(batch.playlist.id, session).comment }.getOrNull()?.contains(MARKER) != true) {
            api.setPlaylistComment(batch.playlist.id, comment(batch.state, json), session)
        }
    }

    private suspend fun libraryAlbum(session: ServerSession, album: WeeklyAlbum): List<Song> {
        val title = Names.normalize(SongMatch.cleanTitle(album.title))
        val artist = Names.normalize(album.artist)
        val hit = api.search(album.title, artistCount = 0, albumCount = 20, songCount = 0, session = session).album.firstOrNull {
            Names.normalize(SongMatch.cleanTitle(it.name)) == title && Names.normalize(it.artistLabel).let { a -> a == artist || a.contains(artist) }
        } ?: return emptyList()
        return api.album(hit.id, session).song
    }

    /** Deletes an old week: its albums (and the artists it added) unless something of them is liked or in another playlist. */
    private suspend fun cleanUp(config: LidarrConfig, key: String, session: ServerSession, batch: WeeklyBatch) {
        val kept = keptAlbums(session, batch)
        val doomed = batch.state.albums.filterNot { album -> kept.any { it.matches(album) } }
        val keepArtists = batch.state.albums.filter { album -> kept.any { it.matches(album) } }.map { it.artistId }.toSet()
        for ((artistId, albums) in doomed.groupBy { it.artistId }) {
            val otherWanted = runCatching { lidarr.albums(config, key, artistId) }.getOrDefault(emptyList())
                .any { it.monitored && albums.none { a -> a.lidarrId == it.id } }
            if (albums.first().newArtist && artistId !in keepArtists && !otherWanted) {
                runCatching { lidarr.deleteArtist(config, key, artistId, exclude = false) }
            } else {
                albums.forEach { runCatching { lidarr.deleteAlbum(config, key, it.lidarrId, exclude = false) } }
            }
        }
        api.deletePlaylist(batch.playlist.id, session)
    }

    private class AlbumKey(val title: String, val artist: String) {
        fun matches(album: WeeklyAlbum) = Names.normalize(SongMatch.cleanTitle(album.title)) == title &&
            Names.normalize(album.artist).let { it == artist || artist.contains(it) || it.contains(artist) }
    }

    /** Albums of the batch with a liked song, or a song in a playlist other than the batch's. */
    private suspend fun keptAlbums(session: ServerSession, batch: WeeklyBatch): List<AlbumKey> {
        val songs = api.starred(session).song.toMutableList()
        songs += api.starred(session).album.map { Song(id = it.id, album = it.name, artist = it.artistLabel) }
        for (playlist in api.playlists(session)) {
            if (playlist.id == batch.playlist.id || parse(playlist, json) != null) continue
            songs += runCatching { api.playlist(playlist.id, session).entry }.getOrDefault(emptyList())
        }
        return songs.mapNotNull { song ->
            val album = song.album ?: return@mapNotNull null
            AlbumKey(Names.normalize(SongMatch.cleanTitle(album)), Names.normalize(song.artistLabel))
        }
    }

    /** When this device last started a run; a run that found nothing isn't retried right away. */
    private val attemptFile get() = File(runFile.parentFile, runFile.name + ".last")

    /** Present once the old Brainarr-based setup has been taken over. */
    private val movedFile get() = File(runFile.parentFile, runFile.name + ".from-brainarr")

    private fun readRun(): RunInProgress? = runCatching { json.decodeFromString(RunInProgress.serializer(), runFile.readText()) }.getOrNull()

    private fun writeRun(run: RunInProgress) {
        runFile.writeText(json.encodeToString(RunInProgress.serializer(), run))
    }

    companion object {
        const val MARKER = "[tonearm-weekly]"
        const val SETTINGS_KEY = "weekly-picks"
        const val WEEK = 7 * 24 * 3_600_000L
        /** Runs may start up to this much early, so a weekly check-in doesn't drift later every week. */
        private const val SLACK = 2 * 3_600_000L
        private const val ABANDONED = 24 * 3_600_000L
        private const val RETRY = 12 * 3_600_000L
        const val DEFAULT_ALBUMS = 5
        private const val OLD_LIST_PREFIX = "Tonearm "
        private const val OLD_WEEKLY_LIST = "Tonearm weekly picks"

        private val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun parse(playlist: Playlist, json: Json = compact): WeeklyState? {
            val text = playlist.comment?.substringAfter(MARKER, "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { json.decodeFromString(WeeklyState.serializer(), text) }.getOrNull()
        }

        fun comment(state: WeeklyState, json: Json = compact): String =
            "AI picks for the week of ${date(state.created)}. Deleted when next week's arrive, unless you like this playlist.\n" +
                MARKER + compact.encodeToString(WeeklyState.serializer(), state)

        fun playlistName(created: Long) = "Weekly picks · ${date(created)}"

        fun date(millis: Long): String =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
    }
}
