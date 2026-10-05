package io.github.deadeyebarb.tonearm.weekly

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.integrations.BrainarrList
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** The run a device started and is waiting for: Lidarr's command and what was there before. */
@Serializable
private data class RunInProgress(
    val playlistId: String,
    val commandId: Int,
    val started: Long,
    val monitoredBefore: Set<Int>,
    val artistsBefore: Set<Int>,
    val listId: Int? = null,
)

/**
 * Brainarr's weekly picks: once a week a dedicated Brainarr list in Lidarr picks albums, Lidarr
 * downloads them, and a playlist on the music server collects them. A week later, when the next
 * selection arrives, the old playlist and its music are deleted again, except albums with a song you
 * liked or put in another playlist. Liking the playlist itself (and naming it) keeps it all.
 *
 * The weekly playlist's comment holds the state, so the phone and the desktop share it. [runFile] is
 * this device's note of a run it started.
 */
class WeeklyPicks(
    private val api: SubsonicApi,
    private val lidarr: LidarrClient,
    private val json: Json,
    private val deviceId: String,
    private val runFile: File,
) {
    /** The weekly list in Lidarr; its presence means weekly picks are on. */
    suspend fun weeklyList(config: LidarrConfig, key: String): BrainarrList? =
        lidarr.importLists(config, key).firstOrNull { (list, _) -> list.name == LIST_NAME }
            ?.let { (list, raw) -> BrainarrList.from(list, raw) }

    /**
     * Turns weekly picks on: a copy of [main] that picks [albums] specific albums, monitors and
     * searches them, and doesn't run on Lidarr's own schedule. Returns true when Lidarr hides the
     * AI provider's API key, which then has to be entered once for the new list in Lidarr.
     */
    suspend fun enable(config: LidarrConfig, key: String, main: BrainarrList, albums: Int): Boolean {
        val body = weeklyListBody(main.raw, albums)
        weeklyList(config, key)?.let { existing ->
            lidarr.updateImportList(config, key, existing.id, JsonObject(body + ("id" to JsonPrimitive(existing.id))))
            return false
        }
        lidarr.createImportList(config, key, body)
        return hasMaskedSecret(main.raw)
    }

    suspend fun disable(config: LidarrConfig, key: String) {
        weeklyList(config, key)?.let { lidarr.deleteImportList(config, key, it.id) }
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
        var news: String? = finishRun(config, key, session, now)
        val list = weeklyList(config, key)
        var batches = batches(session)
        // A run whose device went away doesn't block the next week forever.
        batches.filter { it.state.status == WeeklyState.RUNNING && now - it.state.created > ABANDONED }.forEach {
            api.deletePlaylist(it.playlist.id, session)
        }
        batches = batches.filterNot { it.state.status == WeeklyState.RUNNING && now - it.state.created > ABANDONED }
        // A run that ended without switching its list off again (the app quit): switch it off now.
        if (list != null && list.automaticAdd && !runFile.exists()) runCatching { lidarr.setImportListAutomaticAdd(config, key, list.id, false) }
        val newest = batches.maxByOrNull { it.state.created }
        val lastAttempt = attemptFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val due = newest == null || now - newest.state.created >= WEEK - SLACK
        if (list != null && due && !runFile.exists() && now - lastAttempt >= RETRY) {
            attemptFile.writeText(now.toString())
            news = startRun(config, key, session, list, now) ?: news
            batches = batches(session)
        }
        val current = batches.maxByOrNull { it.state.created }
        val gone = mutableSetOf<String>()
        for (old in batches) {
            val replaced = old !== current && current != null && old.state.created < current.state.created && current.state.status == WeeklyState.READY
            val lapsed = list == null && now >= old.expires
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
        api.setPlaylistComment(batch.playlist.id, "Kept from Brainarr's weekly picks of ${date(batch.state.created)}", session)
    }

    private suspend fun startRun(config: LidarrConfig, key: String, session: ServerSession, list: BrainarrList, now: Long): String? {
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
            val monitored = lidarr.albums(config, key).filter { it.monitored }.map { it.id }.toSet()
            val artists = lidarr.artists(config, key).map { it.id }.toSet()
            lidarr.setImportListAutomaticAdd(config, key, list.id, true)
            val command = lidarr.startCommand(config, key, "ImportListSync", "definitionId" to list.id)
            writeRun(RunInProgress(playlist.id, command.id, now, monitored, artists, list.id))
        } catch (e: Exception) {
            runCatching { lidarr.setImportListAutomaticAdd(config, key, list.id, false) }
            api.deletePlaylist(playlist.id, session)
            throw e
        }
        return "Brainarr is picking this week's albums"
    }

    private suspend fun finishRun(config: LidarrConfig, key: String, session: ServerSession, now: Long): String? {
        val run = readRun() ?: return null
        val command = runCatching { lidarr.command(config, key, run.commandId) }.getOrNull()
        if (command != null && !command.finished && now - run.started < ABANDONED) return null
        runFile.delete()
        run.listId?.let { runCatching { lidarr.setImportListAutomaticAdd(config, key, it, false) } }
        val playlist = runCatching { api.playlist(run.playlistId, session) }.getOrNull() ?: return null
        val state = parse(playlist, json) ?: return null
        if (command == null || command.status != "completed") {
            api.deletePlaylist(playlist.id, session)
            return "Brainarr's weekly run didn't work: ${command?.message ?: "Lidarr lost track of it"}"
        }
        val artists = lidarr.artists(config, key).associateBy { it.id }
        val added = lidarr.albums(config, key).filter { it.monitored && it.id !in run.monitoredBefore }.map { album ->
            WeeklyAlbum(
                lidarrId = album.id,
                title = album.title,
                artist = artists[album.artistId]?.artistName ?: album.artist?.artistName.orEmpty(),
                artistId = album.artistId,
                newArtist = album.artistId !in run.artistsBefore,
            )
        }
        if (added.isEmpty()) {
            api.deletePlaylist(playlist.id, session)
            return "Brainarr found nothing new this week"
        }
        api.setPlaylistComment(playlist.id, comment(state.copy(status = WeeklyState.READY, albums = added), json), session)
        return "This week's picks: ${added.size} albums are downloading"
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
                runCatching { lidarr.deleteArtist(config, key, artistId) }
            } else {
                albums.forEach { runCatching { lidarr.deleteAlbum(config, key, it.lidarrId) } }
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

    private fun readRun(): RunInProgress? = runCatching { json.decodeFromString(RunInProgress.serializer(), runFile.readText()) }.getOrNull()

    private fun writeRun(run: RunInProgress) {
        runFile.writeText(json.encodeToString(RunInProgress.serializer(), run))
    }

    companion object {
        const val LIST_NAME = "Tonearm weekly picks"
        const val MARKER = "[tonearm-weekly]"
        const val WEEK = 7 * 24 * 3_600_000L
        /** Runs may start up to this much early, so a weekly check-in doesn't drift later every week. */
        private const val SLACK = 2 * 3_600_000L
        private const val ABANDONED = 24 * 3_600_000L
        private const val RETRY = 12 * 3_600_000L
        const val DEFAULT_ALBUMS = 5

        private val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun parse(playlist: Playlist, json: Json = compact): WeeklyState? {
            val text = playlist.comment?.substringAfter(MARKER, "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { json.decodeFromString(WeeklyState.serializer(), text) }.getOrNull()
        }

        fun comment(state: WeeklyState, json: Json = compact): String =
            "Brainarr's picks for the week of ${date(state.created)}. Deleted when next week's arrive, unless you like this playlist.\n" +
                MARKER + compact.encodeToString(WeeklyState.serializer(), state)

        fun playlistName(created: Long) = "Weekly picks · ${date(created)}"

        fun date(millis: Long): String =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

        fun weeklyListBody(main: JsonObject, albums: Int): JsonObject {
            val fields = (main["fields"] as? JsonArray).orEmpty().map { element ->
                val field = element.jsonObject
                when (field["name"]?.jsonPrimitive?.contentOrNull) {
                    "recommendationMode" -> JsonObject(field + ("value" to JsonPrimitive("specificAlbums")))
                    "maxRecommendations" -> JsonObject(field + ("value" to JsonPrimitive(albums)))
                    else -> field
                }
            }
            return JsonObject(
                main - "id" + mapOf(
                    "name" to JsonPrimitive(LIST_NAME),
                    "enableAutomaticAdd" to JsonPrimitive(false),
                    "shouldMonitor" to JsonPrimitive("specificAlbum"),
                    "shouldMonitorExisting" to JsonPrimitive(true),
                    "shouldSearch" to JsonPrimitive(true),
                    "monitorNewItems" to JsonPrimitive("none"),
                    "fields" to JsonArray(fields),
                ),
            )
        }

        /** Lidarr sends password fields back as asterisks; a copy made from them has no real key. */
        fun hasMaskedSecret(raw: JsonObject): Boolean = (raw["fields"] as? JsonArray).orEmpty().any { element ->
            val field = element as? JsonObject ?: return@any false
            val value = (field["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            value.isNotEmpty() && value.all { it == '*' } && (field["type"]?.jsonPrimitive?.contentOrNull == "password" || "key" in field["name"].toString().lowercase())
        }

        private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
    }
}
