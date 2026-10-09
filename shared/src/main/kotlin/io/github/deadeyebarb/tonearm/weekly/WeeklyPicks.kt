package io.github.deadeyebarb.tonearm.weekly

import io.github.deadeyebarb.tonearm.connect.AiPick
import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.PicksFolder
import io.github.deadeyebarb.tonearm.connect.ConnectRoute
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** An album a weekly run added in Lidarr. */
@Serializable
data class WeeklyAlbum(
    val lidarrId: Int,
    val title: String,
    val artist: String,
    val artistId: Int,
    /** The run added the artist too (so it can go again with the album). */
    val newArtist: Boolean,
    /**
     * The album's standout songs as the AI named them, which make the week's playlist; empty when it named none (its
     * first few songs then). Null in weeks from before the playlist was made of songs: the whole album goes in.
     */
    val songs: List<String>? = null,
    /** Its release group (MusicBrainz) id, to find it in Lidarr again. */
    val foreignId: String? = null,
) {
    /** Whether it's the album [pick] names. */
    fun isPick(pick: AiPick) = Names.normalize(SongMatch.cleanTitle(title)) == Names.normalize(SongMatch.cleanTitle(pick.album)) &&
        Names.normalize(artist) == Names.normalize(pick.artist)
}

/** What a weekly playlist's comment carries after [WeeklyPicks.MARKER]. */
@Serializable
data class WeeklyState(
    val created: Long,
    /** The device that started the run; only it finishes a [RUNNING] one. */
    val by: String,
    val status: String = RUNNING,
    val albums: List<WeeklyAlbum> = emptyList(),
    val message: String? = null,
    /**
     * Albums whose request got no clear answer (Lidarr was slow, the proxy gave up), which Lidarr may add all the same:
     * looked for during the first day, and this week's albums once they're there. Their [WeeklyAlbum.lidarrId] is 0 when
     * Lidarr didn't have them before.
     */
    val unsure: List<WeeklyAlbum> = emptyList(),
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
private data class RunInProgress(
    val playlistId: String,
    val started: Long,
    val albums: Int = WeeklyPicks.DEFAULT_ALBUMS,
    /** When the picks there were before were made, by the server's clock; null in runs from before, which use [started]. */
    val since: Long? = null,
    /** What the run has had Lidarr get so far, so a check after a failure carries on from there. */
    val added: List<WeeklyAlbum> = emptyList(),
    val unsure: List<WeeklyAlbum> = emptyList(),
    /** The album Lidarr is being asked for: when the check is cut off meanwhile, the next one asks Lidarr whether it got it. */
    val pending: WeeklyAlbum? = null,
) {
    /** It has had Lidarr get albums: the week is on, whatever the AI does now. */
    val underway: Boolean get() = added.isNotEmpty() || unsure.isNotEmpty() || pending != null
}

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
    /** On unless switched off: the week's picks download to the server by themselves. */
    suspend fun settings(session: ServerSession): WeeklySettings =
        connect.storeGet(ConnectRoute.Server(session), SETTINGS_KEY).value
            ?.let { runCatching { json.decodeFromString(WeeklySettings.serializer(), it) }.getOrNull() } ?: WeeklySettings(on = true)

    suspend fun saveSettings(session: ServerSession, settings: WeeklySettings) {
        val route = ConnectRoute.Server(session)
        repeat(4) {
            val stored = connect.storeGet(route, SETTINGS_KEY)
            if (connect.storePut(route, SETTINGS_KEY, json.encodeToString(WeeklySettings.serializer(), settings), stored.version) != null) {
                // Switched on: the next check tries straight away, rather than after the pause between tries.
                if (settings.on) {
                    attemptFile.delete()
                    waitingFile.delete()
                }
                return
            }
        }
        throw IOException("Couldn't save the weekly picks setting; try again")
    }

    /** The user's own weekly playlists. */
    suspend fun batches(session: ServerSession): List<WeeklyBatch> = batchesOf(own(session))

    private fun batchesOf(playlists: List<Playlist>) = playlists.mapNotNull { playlist -> parse(playlist, json)?.let { WeeklyBatch(playlist, it) } }

    /**
     * The user's own playlists. Navidrome lists an admin everyone's, and everyone the public ones, but another user's
     * weekly picks are theirs to look after.
     */
    private suspend fun own(session: ServerSession): List<Playlist> {
        val me = me(session)
        return api.playlists(session).filter { it.ownedBy(me) }
    }

    /** Who is logged in: the login says, and for an API key the Tonearm server does. */
    private suspend fun me(session: ServerSession): String? =
        session.user ?: users[session.id] ?: connect.findServer(session)?.user?.ifEmpty { null }?.also { users[session.id] = it }

    private val users = ConcurrentHashMap<String, String>()

    /**
     * Does what's due: finishes this device's run, starts this week's when the newest is a week old,
     * deletes the weeks before it, and fills the playlists with what has downloaded. Returns news for
     * the user, if any.
     */
    suspend fun tick(config: LidarrConfig, key: String, session: ServerSession, now: Long = System.currentTimeMillis()): String? =
        ticking.withLock { checkNow(config, key, session, now) }

    /**
     * One check at a time: the hourly one, the one soon after a run started and one asked for now can meet, and would
     * both ask Lidarr for the same run's albums.
     */
    private val ticking = Mutex()

    private suspend fun checkNow(config: LidarrConfig, key: String, session: ServerSession, now: Long): String? {
        runFile.parentFile?.mkdirs()
        // Only admins had (and can remove) the Brainarr lists.
        val settings = (if (config.limited) null else moveFromBrainarr(config, key, session)) ?: settings(session)
        var news: String? = finishRun(config, key, session, now)
        val lists = own(session)
        var batches = batchesOf(lists)
        // A run whose device went away doesn't block the next week forever.
        batches.filter { it.state.status == WeeklyState.RUNNING && now - it.state.created > ABANDONED }.forEach {
            api.deletePlaylist(it.playlist.id, session)
        }
        batches = batches.filterNot { it.state.status == WeeklyState.RUNNING && now - it.state.created > ABANDONED }
        // A week kept for good is still this week's.
        val last = listOfNotNull(batches.maxOfOrNull { it.state.created }, lists.mapNotNull { keptCreated(it) }.maxOrNull()).maxOrNull()
        val lastAttempt = attemptFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val due = last == null || now - last >= WEEK - SLACK
        if (settings.on && due && !runFile.exists() && now - lastAttempt >= RETRY) {
            attemptFile.writeText(now.toString())
            news = startRun(session, settings, now, config.limited) ?: news
            batches = batches(session)
        }
        // Albums whose request got no clear answer and that Lidarr has after all are the week's too (looked for on its first day).
        batches = batches.map { batch ->
            val unsettled = batch.state.status == WeeklyState.READY && batch.state.unsure.isNotEmpty() && now - batch.state.created < ABANDONED
            if (unsettled) orNull { settle(config, key, session, batch) } ?: batch else batch
        }
        val current = batches.maxByOrNull { it.state.created }
        // The week's albums Lidarr has let go of before they arrived are wanted again.
        current?.takeIf { it.state.status == WeeklyState.READY && now < it.expires }?.let { rewant(config, key, it) }
        val gone = mutableSetOf<String>()
        for (old in batches) {
            val replaced = old !== current && current != null && old.state.created < current.state.created && current.state.status == WeeklyState.READY
            val lapsed = !settings.on && now >= old.expires
            if (old.state.status == WeeklyState.READY && (replaced || lapsed)) {
                cleanUp(config, key, session, old)
                gone += old.playlist.id
            }
        }
        if (gone.isNotEmpty()) {
            // Weeks that stay keep their artists; anything else in the user's own folder with nothing wanted or on disk goes.
            val inUse = batches.filter { it.playlist.id !in gone }.flatMap { b -> b.state.albums.map { it.artistId } }.toSet()
            runCatching { connect.picksFolder(session) }.getOrNull()?.takeIf { it.ready }?.let { sweep(config, key, it.path, inUse) }
        }
        batches.filter { it.state.status == WeeklyState.READY && it.playlist.id !in gone }.forEach { runCatching { fill(session, it) } }
        return news
    }

    /** Keeps a weekly playlist and its music for good, under [name]. When its week was made stays in it, so the next one still waits a week. */
    suspend fun keep(session: ServerSession, batch: WeeklyBatch, name: String) {
        api.renamePlaylist(batch.playlist.id, name.trim(), session)
        api.setPlaylistComment(batch.playlist.id, "Kept from the weekly picks of ${date(batch.state.created)}\n$KEPT_MARKER${batch.state.created}", session)
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

    private suspend fun startRun(session: ServerSession, settings: WeeklySettings, now: Long, limited: Boolean): String? {
        // With a folder of their own for each user, it has to be there before the AI is asked.
        // Checking costs next to nothing: it's asked again at the next check, not after the pause between tries. Only news
        // when it's different from before.
        val folder = try {
            connect.picksFolder(session)
        } catch (e: Exception) {
            attemptFile.delete()
            throw e
        }
        folderProblem(folder, limited)?.let { problem ->
            val before = waiting()
            waitFor(problem)
            attemptFile.delete()
            return problem.takeIf { it != before }
        }
        waitingFile.delete()
        val state = WeeklyState(created = now, by = deviceId)
        val me = me(session)
        val playlist = api.createPlaylist(playlistName(now), emptyList(), session)
            ?: api.playlists(session).firstOrNull { it.name == playlistName(now) && parse(it, json) == null && it.ownedBy(me) }
            ?: throw IOException("The music server didn't create the weekly playlist")
        api.setPlaylistComment(playlist.id, comment(state, json), session)
        // Phone and desktop may start at the same moment: the earlier claim wins. A week kept already counts too.
        val lists = own(session)
        val rivals = batchesOf(lists).filter { it.playlist.id != playlist.id && now - it.state.created < WEEK - SLACK }
        val kept = lists.any { keptCreated(it)?.let { created -> now - created < WEEK - SLACK } == true }
        if (kept || rivals.any { it.state.created < now || (it.state.created == now && it.playlist.id < playlist.id) }) {
            api.deletePlaylist(playlist.id, session)
            return null
        }
        val refused = try {
            // The picks there are now, so the new ones are told apart by the server's own clock.
            val before = connect.aiPicks(session)
            val asked = connect.aiPicks(session, refresh = true)
            // Nothing started (the server couldn't read what you play, say): no use waiting a day for it.
            if (!asked.running && asked.madeAt <= before.madeAt) {
                asked.problem ?: "the AI didn't start"
            } else {
                writeRun(RunInProgress(playlist.id, now, settings.albums, since = before.madeAt))
                null
            }
        } catch (e: Exception) {
            api.deletePlaylist(playlist.id, session)
            throw e
        }
        if (refused != null) {
            api.deletePlaylist(playlist.id, session)
            return "This week's picks didn't work: $refused"
        }
        return "Picking this week's albums from what you play"
    }

    /**
     * Finishes this device's run once the AI has answered. The run file stays until what came of it is saved in the
     * playlist, so when Lidarr or the music server is away for a moment the next check carries on.
     */
    private suspend fun finishRun(config: LidarrConfig, key: String, session: ServerSession, now: Long): String? {
        if (!runFile.exists()) return null
        val run = readRun()
        if (run == null) {
            // Cut off while it was written: nothing to carry on from, and it would hold back every week after. Its
            // playlist goes with the abandoned ones.
            runFile.delete()
            return null
        }
        val picks = runCatching { connect.aiPicks(session) }.getOrNull()
        val since = run.since ?: run.started
        val fresh = picks != null && !picks.running && picks.madeAt > since
        val failed = picks != null && !picks.running && picks.madeAt <= since && picks.problem != null
        // Waits for the AI. A run a check was cut off in waits for the picks to carry on with, unless they're gone.
        val waiting = if (run.underway) picks == null || picks.running else !fresh && !failed
        if (waiting && now - run.started < ABANDONED) return null
        val folder = if (fresh) connect.picksFolder(session) else null
        val playlist = try {
            api.playlist(run.playlistId, session)
        } catch (e: SubsonicApiException) {
            if (e.code != 70) throw e
            null
        }
        // Deleted meanwhile, or not this user's: nothing to finish.
        val state = playlist?.takeIf { it.ownedBy(me(session)) }?.let { parse(it, json) }
        if (playlist == null || state == null) {
            runFile.delete()
            return null
        }
        val settled = settlePending(config, key, run, now)
        if (!settled.underway) {
            val problem = when {
                !fresh -> "This week's picks didn't work: ${picks?.problem ?: "the AI took too long"}"
                else -> folderProblem(folder, config.limited)?.let(::waitFor)
            }
            if (problem != null) {
                api.deletePlaylist(playlist.id, session)
                runFile.delete()
                return problem
            }
        }
        val done = if (fresh) request(config, key, picks.picks, settled, now, folder?.path) else settled
        if (!done.underway) {
            api.deletePlaylist(playlist.id, session)
            runFile.delete()
            return "Nothing new to get from the AI picks this week"
        }
        api.setPlaylistComment(playlist.id, comment(state.copy(status = WeeklyState.READY, albums = done.added, unsure = done.unsure), json), session)
        runFile.delete()
        return "This week's picks: ${done.added.size + done.unsure.size} albums are downloading"
    }

    /**
     * Has Lidarr get the first picks it knows and doesn't already want, up to the run's count, into [folder] when the user
     * has a folder of their own: then only for artists that are new to Lidarr or already in that folder, since an album
     * always goes where its artist is (the shared library, or someone else's picks). Carries on from what the run got
     * before, and notes each album in the run file as it goes. When Lidarr can't be asked, the check stops there and the
     * next one carries on, for a day; after that the week is what it got.
     */
    private suspend fun request(config: LidarrConfig, key: String, picks: List<AiPick>, run: RunInProgress, now: Long, folder: String? = null): RunInProgress {
        var progress = run
        val retrying = now - run.started < ABANDONED
        val artists = lidarr.artists(config, key)
        val artistsBefore = artists.map { it.id }.toSet()
        val defaults = lidarr.resolveDefaults(config, key).copy(search = true).let { d -> folder?.let { d.copy(rootFolderPath = it) } ?: d }
        for (pick in picks) {
            if (progress.added.size + progress.unsure.size >= run.albums) break
            if ((progress.added + progress.unsure).any { it.isPick(pick) }) continue
            val found = try {
                lidarr.lookupAlbum(config, key, "${pick.artist} ${pick.album}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lidarr's metadata server is down, say: not the same as finding nothing.
                if (retrying && e is IntegrationHttpException && e.code !in 400..499) throw e
                emptyList()
            }
            val hit = found.firstOrNull {
                Names.normalize(SongMatch.cleanTitle(it.title)) == Names.normalize(SongMatch.cleanTitle(pick.album)) &&
                    Names.normalize(it.artistName.orEmpty()) == Names.normalize(pick.artist)
            } ?: continue
            // Already wanted: not this week's to delete later.
            if (hit.inLidarr && hit.monitored) continue
            val artist = artists.firstOrNull { (hit.artistId != 0 && it.id == hit.artistId) || (hit.artistForeignId != null && it.foreignArtistId == hit.artistForeignId) }
            if (folder != null && artist != null && !isIn(artist.path, folder)) continue
            // An artist in the user's own folder came with earlier picks: they can go with this week's too, unless
            // something else of theirs is kept (the clean-up checks).
            val ownArtist = folder != null && artist != null
            val album = WeeklyAlbum(hit.lidarrId, hit.title, hit.artistName ?: pick.artist, hit.artistId, newArtist = ownArtist, songs = pick.songs, foreignId = hit.foreignId.ifEmpty { null })
            // Noted before it's sent: Lidarr may get it although the check is cut off before the answer.
            val asked = album.copy(newArtist = ownArtist || artist == null)
            progress = progress.copy(pending = asked).also(::writeRun)
            val wanted = try {
                if (hit.inLidarr) {
                    lidarr.monitorAlbum(config, key, hit.lidarrId, search = false)
                    // Wanted from here on, so it's this week's even when the search doesn't go through.
                    orNull { lidarr.searchAlbum(config, key, hit.lidarrId) }
                    album
                } else {
                    val added = lidarr.addAlbum(config, key, hit.candidate, defaults)
                    album.copy(lidarrId = added.id, title = added.title, artist = added.artist?.artistName ?: album.artist, artistId = added.artistId,
                        newArtist = ownArtist || added.artistId !in artistsBefore)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lidarr said no.
                if (e is IntegrationHttpException && e.code in 400..499) {
                    progress = progress.copy(pending = null).also(::writeRun)
                    continue
                }
                // No answer isn't a no: Lidarr goes on with an add after the proxy has given up waiting. When Lidarr can't
                // say either, the album stays pending for the next check to ask about.
                val there = try {
                    wantedNow(config, key, album)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (retrying) throw e
                    null
                }
                if (there == null) {
                    // Looked for again on the next checks; it counts toward the week's albums meanwhile.
                    val unsure = if (album.foreignId != null) progress.unsure + asked else progress.unsure
                    progress = progress.copy(pending = null, unsure = unsure).also(::writeRun)
                    continue
                }
                album.copy(lidarrId = there.lidarrId, artistId = there.artistId, newArtist = ownArtist || there.artistId !in artistsBefore)
            }
            progress = progress.copy(pending = null, added = progress.added + wanted).also(::writeRun)
        }
        return progress
    }

    /**
     * [album] as Lidarr has it, when it wants it: monitored, or there at all when it wasn't before ([WeeklyAlbum.lidarrId] 0).
     * Null when Lidarr says it doesn't; throws when Lidarr can't be asked.
     */
    private suspend fun wantedNow(config: LidarrConfig, key: String, album: WeeklyAlbum): LidarrClient.AlbumHit? {
        val id = album.foreignId?.takeIf { it.isNotEmpty() } ?: return null
        val now = lidarr.lookupAlbum(config, key, "lidarr:$id").firstOrNull { it.foreignId == id } ?: return null
        return now.takeIf { it.inLidarr && (it.monitored || album.lidarrId == 0) }
    }

    /**
     * Settles the album a check was cut off asking for: the week's when Lidarr has it, else asked for again. While Lidarr
     * can't say, the check stops here and the next one asks; after a day it's one of the unsure ones.
     */
    private suspend fun settlePending(config: LidarrConfig, key: String, run: RunInProgress, now: Long): RunInProgress {
        val album = run.pending ?: return run
        val there = try {
            wantedNow(config, key, album)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (now - run.started < ABANDONED) throw e
            return run.copy(pending = null, unsure = run.unsure + album).also(::writeRun)
        }
        val added = listOfNotNull(there?.let { album.copy(lidarrId = it.lidarrId, artistId = it.artistId) })
        return run.copy(pending = null, added = run.added + added).also(::writeRun)
    }

    /** Makes the batch's unsure albums that Lidarr has after all its own, and saves that. */
    private suspend fun settle(config: LidarrConfig, key: String, session: ServerSession, batch: WeeklyBatch): WeeklyBatch {
        val arrived = batch.state.unsure.mapNotNull { album -> orNull { wantedNow(config, key, album) }?.let { album to album.copy(lidarrId = it.lidarrId, artistId = it.artistId) } }
        if (arrived.isEmpty()) return batch
        val state = batch.state.copy(albums = batch.state.albums + arrived.map { it.second }, unsure = batch.state.unsure - arrived.map { it.first }.toSet())
        api.setPlaylistComment(batch.playlist.id, comment(state, json), session)
        return batch.copy(state = state)
    }

    /** What [block] gives, or null when it fails; a cancelled check still stops. */
    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Puts the downloaded standout songs of the batch's albums into its playlist, the albums taking turns like on a
     * mixtape (whole albums, in album order, for weeks from before).
     */
    private suspend fun fill(session: ServerSession, batch: WeeklyBatch) {
        val songs = playlistOf(batch.state.albums.map { it to libraryAlbum(session, it) })
        val ids = songs.map { it.id }
        val current = runCatching { api.playlist(batch.playlist.id, session).entry.map { it.id } }.getOrDefault(emptyList())
        if (ids.isEmpty() || ids == current) return
        api.replacePlaylist(batch.playlist.id, ids, session)
        // Some servers reset the comment with the songs; it holds the state, so put it back.
        if (runCatching { api.playlist(batch.playlist.id, session).comment }.getOrNull()?.contains(MARKER) != true) {
            api.setPlaylistComment(batch.playlist.id, comment(batch.state, json), session)
        }
    }

    /**
     * Why the week can't download: the user's own picks folder isn't set up yet, or there are no picks folders and only
     * the server keeps a non-admin's picks apart (without, they'd land in everyone's library for good).
     */
    private fun folderProblem(folder: PicksFolder?, limited: Boolean): String? = when {
        folder == null -> if (limited) "Weekly picks need a picks folder of your own on the Tonearm server (PICKS_FOLDER)" else null
        !folder.ready -> "Weekly picks wait for your own picks folder: ${folder.problem}"
        else -> null
    }

    private fun isIn(path: String?, folder: String) = path != null && path.trimEnd('/').startsWith(folder.trimEnd('/') + "/")

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

    /**
     * Monitors (and searches for) the batch's albums that are neither monitored nor on disk anymore: Lidarr unmonitors a
     * new artist's albums once it has added them, when it isn't told which one was asked for.
     */
    private suspend fun rewant(config: LidarrConfig, key: String, batch: WeeklyBatch) {
        for (album in batch.state.albums) {
            val now = runCatching { lidarr.album(config, key, album.lidarrId) }.getOrNull() ?: continue
            if (!now.monitored && (now.statistics?.trackFileCount ?: 0) == 0) runCatching { lidarr.monitorAlbum(config, key, album.lidarrId, search = true) }
        }
    }

    /** Deletes the artists in the user's own picks [folder] with nothing wanted and nothing on disk, except those weeks still use. */
    private suspend fun sweep(config: LidarrConfig, key: String, folder: String, inUse: Set<Int>) {
        val leftovers = lidarr.artists(config, key).filter { it.id !in inUse && isIn(it.path, folder) && (it.statistics?.trackFileCount ?: 0) == 0 }
        for (artist in leftovers) {
            val wanted = runCatching { lidarr.albums(config, key, artist.id) }.getOrNull()?.any { it.monitored } ?: true
            if (!wanted) runCatching { lidarr.deleteArtist(config, key, artist.id, exclude = false) }
        }
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

    /** Why weekly picks are waiting (for a picks folder of the user's own), as the weekly picks screen shows it; null when they aren't. */
    fun waiting(): String? = runCatching { waitingFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } }.getOrNull()

    private fun waitFor(problem: String): String {
        runCatching { waitingFile.writeText(problem) }
        return problem
    }

    private val waitingFile get() = File(runFile.parentFile, runFile.name + ".waiting")

    /** When this device last started a run; a run that found nothing isn't retried right away. */
    private val attemptFile get() = File(runFile.parentFile, runFile.name + ".last")

    /** Present once the old Brainarr-based setup has been taken over. */
    private val movedFile get() = File(runFile.parentFile, runFile.name + ".from-brainarr")

    private fun readRun(): RunInProgress? = runCatching { json.decodeFromString(RunInProgress.serializer(), runFile.readText()) }.getOrNull()

    /** Through a file of its own first, so a check cut off while writing leaves the run as it was. */
    private fun writeRun(run: RunInProgress) {
        val tmp = File(runFile.parentFile, runFile.name + ".tmp")
        tmp.writeText(json.encodeToString(RunInProgress.serializer(), run))
        if (!tmp.renameTo(runFile)) {
            runFile.delete()
            tmp.renameTo(runFile)
        }
    }

    companion object {
        const val MARKER = "[tonearm-weekly]"
        /** In a kept playlist's comment, before when its week was made (not a [MARKER]: it's no batch anymore). */
        const val KEPT_MARKER = "[tonearm-weekly-kept]"
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

        /** When the week a kept playlist came from was made; null for other playlists, and weeks kept before this was noted. */
        fun keptCreated(playlist: Playlist): Long? = playlist.comment?.substringAfter(KEPT_MARKER, "")?.trim()?.toLongOrNull()

        fun comment(state: WeeklyState, json: Json = compact): String =
            "AI picks for the week of ${date(state.created)}. Deleted when next week's arrive, unless you like this playlist.\n" +
                MARKER + compact.encodeToString(WeeklyState.serializer(), state)

        fun playlistName(created: Long) = "Weekly picks · ${date(created)}"

        /** Songs of each album it has: the ones the AI named (or its first few), the albums taking turns. */
        fun playlistOf(albums: List<Pair<WeeklyAlbum, List<Song>>>): List<Song> {
            if (albums.any { it.first.songs == null }) return albums.flatMap { it.second }
            val picked = albums.map { (album, songs) ->
                val named = album.songs.orEmpty().map { Names.normalize(SongMatch.cleanTitle(it)) }.toSet()
                songs.filter { Names.normalize(SongMatch.cleanTitle(it.title)) in named }.ifEmpty { songs.take(FIRST_SONGS) }
            }
            return (0 until (picked.maxOfOrNull { it.size } ?: 0)).flatMap { i -> picked.mapNotNull { it.getOrNull(i) } }
        }

        /** Songs an album gives the playlist when the AI named none that it has. */
        private const val FIRST_SONGS = 3

        fun date(millis: Long): String =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
    }
}
