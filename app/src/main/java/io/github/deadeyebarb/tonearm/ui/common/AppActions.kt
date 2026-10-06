package io.github.deadeyebarb.tonearm.ui.common

import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.integrations.IntegrationsService
import io.github.deadeyebarb.tonearm.ui.YtAlbumRoute
import io.github.deadeyebarb.tonearm.ui.YtArtistRoute
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.download.DownloadEntry
import io.github.deadeyebarb.tonearm.media.ArtworkCache
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.ui.AlbumRoute
import io.github.deadeyebarb.tonearm.ui.ArtistRoute
import io.github.deadeyebarb.tonearm.ui.GenreRoute
import io.github.deadeyebarb.tonearm.ui.NowPlayingRoute
import io.github.deadeyebarb.tonearm.ui.PlaylistRoute
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Everything a screen can ask the app to do, so rows and menus don't need to know about services. */
class AppActions(
    val container: AppContainer,
    val nav: NavHostController,
    private val requestNotificationPermission: () -> Unit,
) {
    val player get() = container.player
    val activeServerId: String? get() = container.sessions.active.value?.id

    /** Songs waiting for the "Add to playlist" dialog. */
    var playlistPicker by mutableStateOf<List<QueueSong>?>(null)

    /** An artist name waiting for the Lidarr request dialog. */
    var lidarrRequest by mutableStateOf<String?>(null)

    /** Normalized names of artists requested from Lidarr during this session. */
    val requestedArtists = androidx.compose.runtime.mutableStateListOf<String>()

    fun navigate(route: Any) = nav.navigate(route) { launchSingleTop = true }
    fun back() = nav.popBackStack()
    fun openAlbum(id: String) = navigate(AlbumRoute(id))
    fun openArtist(id: String) = navigate(ArtistRoute(id))
    fun openPlaylist(id: String) = navigate(PlaylistRoute(id))
    fun openGenre(name: String) = navigate(GenreRoute(name))
    fun openNowPlaying() = navigate(NowPlayingRoute)
    fun openYouTubeArtist(artist: YtArtist) = navigate(YtArtistRoute(artist.url, artist.name, artist.imageUrl, artist.subscribers))
    fun openYouTubeAlbum(album: YtAlbum) = navigate(YtAlbumRoute(album.url, album.title, album.artist, album.imageUrl))

    /** Opens an artist's YouTube Music page by name. */
    fun findOnYouTube(artist: String) = launch {
        val found = container.catalog.findArtist(artist)
        if (found == null) message("$artist isn't on YouTube Music") else openYouTubeArtist(found)
    }

    /** Plays [first] and then YouTube Music's radio for it. */
    fun playRadio(first: QueueSong) = launch {
        val radio = container.youtube.radio(first.song.id, first.song.artist).map { QueueSong(YouTubeMusic.SOURCE_ID, it) }
        player.play(listOf(first) + radio)
    }

    /** An explicit request for an artist by name (exact matches only). */
    fun requestArtistByName(name: String) = launch {
        val result = container.integrations.requestArtistByName(name, exactOnly = true)
        message(
            when (result) {
                IntegrationsService.ArtistRequestResult.REQUESTED -> "Requested $name in Lidarr"
                IntegrationsService.ArtistRequestResult.ALREADY_IN_LIDARR -> "Lidarr already has $name"
                IntegrationsService.ArtistRequestResult.NOT_FOUND -> "Lidarr has no exact match for $name"
            },
        )
    }

    /** An explicit request for one album. */
    fun requestAlbum(title: String, artist: String) = launch {
        val (config, key) = container.integrations.requireLidarr()
        message(container.songRequests.requestAlbum(config, key, title, artist).message)
    }

    fun entries(songs: List<Song>, serverId: String? = activeServerId): List<QueueSong> =
        serverId?.let { s -> songs.map { QueueSong(s, it) } }.orEmpty()

    fun play(songs: List<Song>, index: Int = 0, shuffle: Boolean = false, context: String? = null) =
        player.play(entries(songs), index, shuffle, context)

    fun playEntries(items: List<QueueSong>, index: Int = 0, shuffle: Boolean = false) = player.play(items, index, shuffle)

    fun playNext(items: List<QueueSong>) {
        player.playNext(items)
        message(if (items.size == 1) "Playing next" else "${items.size} songs will play next")
    }

    fun enqueue(items: List<QueueSong>) {
        player.enqueue(items)
        message(if (items.size == 1) "Added to queue" else "Added ${items.size} songs to queue")
    }

    /** Songs similar to what you've been playing lately, according to Maloja. */
    /** Plays an artist you don't have from YouTube Music; playing it requests it in Lidarr (if that's on). */
    fun playFromYouTube(artist: String) = launch {
        message("Finding $artist on YouTube Music…")
        val songs = container.youtube.artistSongs(artist).map { QueueSong(YouTubeMusic.SOURCE_ID, it) }
        if (songs.isEmpty()) message("Nothing by $artist on YouTube Music") else player.play(songs)
    }

    /** Has Lidarr run the Brainarr list now and says what it added; progress is in `brainarr.asking`. */
    fun askBrainarr() = launch {
        val result = container.brainarr.ask()
        message(
            when {
                result.added.isEmpty() -> "Brainarr found nothing new" + (result.message?.let { " ($it)" } ?: "")
                result.added.size > 3 -> "Brainarr added " + result.added.take(3).joinToString() + " and ${result.added.size - 3} more"
                else -> "Brainarr added " + result.added.joinToString()
            },
        )
    }

    fun playBrainarrMix() = launch {
        message("Building your mix…")
        val songs = container.brainarr.mix()
        if (songs.isEmpty()) message("None of Brainarr's picks are in your library yet") else player.play(songs)
    }

    /** Opens the Lidarr request dialog for an artist, or the Lidarr setup if it isn't connected. */
    fun requestArtist(name: String) {
        if (container.integrations.state.value.lidarr == null) {
            message("Connect Lidarr to request music")
            navigate(io.github.deadeyebarb.tonearm.ui.LidarrSettingsRoute)
        } else {
            lidarrRequest = name
        }
    }

    fun shuffleAll() = launch {
        val session = container.api.active()
        player.play(container.api.randomSongs(200, session).map { QueueSong(session.id, it) })
    }

    /** Plays songs similar to [song] (needs Last.fm / ListenBrainz data on the server). */
    fun instantMix(item: QueueSong) = launch {
        val similar = container.api.similarSongs(item.song.id)
        if (similar.isEmpty()) message("The server has no similar songs for this one") else player.play(listOf(item) + entries(similar))
    }

    fun download(items: List<QueueSong>) {
        // Songs on the phone are there already. A YouTube Music song is downloaded as your library's own
        // file (the FLAC Lidarr got) when the library has it; YouTube itself isn't downloaded.
        val youtube = items.filter { YouTubeMusic.isYouTube(it.serverId) }
        @Suppress("NAME_SHADOWING") val items = items.filterNot { YouTubeMusic.isYouTube(it.serverId) || LocalMusic.isLocal(it.serverId) }
        if (youtube.isNotEmpty()) {
            launch {
                val session = container.sessions.awaitActive()
                val found = youtube.mapNotNull { container.versions.better(it, session, force = true) }
                if (found.isNotEmpty()) downloadNow(found)
                val missing = youtube.size - found.size
                if (missing > 0) message(if (missing == 1) "That song isn't in your library yet; like it to request it in Lidarr" else "$missing songs aren't in your library yet; like them to request them")
            }
        }
        if (items.isNotEmpty()) downloadNow(items)
    }

    private fun downloadNow(items: List<QueueSong>) {
        requestNotificationPermission()
        container.downloads.download(items)
        saveArtwork(items)
        val wifiOnly = container.settings.state.value.downloadOnWifiOnly
        message(
            buildString {
                append(if (items.size == 1) "Downloading 1 song" else "Downloading ${items.size} songs")
                if (wifiOnly && container.network.isMetered()) append(" when you're on Wi-Fi")
            },
        )
    }

    /** Fetches every cover size the UI uses into the image cache, plus the system-UI artwork cache. */
    private fun saveArtwork(items: List<QueueSong>) {
        val covers = items.mapNotNull { item -> item.song.coverArt?.let { item.serverId to it } }.distinct()
        container.scope.launch(Dispatchers.IO) {
            for ((serverId, coverId) in covers) {
                val session = container.sessions.session(serverId) ?: continue
                for (size in CoverSize.ALL) container.imageLoader.execute(coverRequest(container.app, session, coverId, size))
                runCatching { ArtworkCache.fetch(container.app, serverId, coverId) }
            }
        }
    }

    fun removeDownloads(items: List<QueueSong>) {
        container.downloads.remove(items.map { container.downloads.key(it.serverId, it.song.id) })
        message(if (items.size == 1) "Download removed" else "Removed ${items.size} downloads")
    }

    fun setStarred(serverId: String, kind: StarKind, id: String, starred: Boolean) = launch {
        container.starred.set(serverId, kind, id, starred)
    }

    /** The like button: stars a library song, or requests a YouTube Music one and likes it when it arrives. */
    fun setLiked(entry: QueueSong, liked: Boolean) = launch {
        container.likes.set(entry, liked)
    }

    fun addToPlaylist(items: List<QueueSong>) {
        playlistPicker = items
    }

    fun message(text: String) = container.messages.show(text)

    /** Runs [block] and turns failures into a snackbar. */
    fun launch(block: suspend () -> Unit): Job = container.scope.launch(Dispatchers.Main) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(e.userMessage())
        }
    }
}

val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

/** Download state for every song, keyed by "serverId/songId". */
val LocalDownloads = staticCompositionLocalOf<Map<String, DownloadEntry>> { emptyMap() }

/** Favorites toggled this session, keyed by "serverId/id". */
val LocalStarOverrides = staticCompositionLocalOf<Map<String, Boolean>> { emptyMap() }

/** Video ids of YouTube Music songs liked before they're in the library. */
val LocalPendingLikes = staticCompositionLocalOf<Set<String>> { emptySet() }

/** Ids of liked songs stored on the phone. */
val LocalPhoneLikes = staticCompositionLocalOf<Set<String>> { emptySet() }

/** Whether playback is running (for "now playing" indicators). */
val LocalPlaying = staticCompositionLocalOf { false }

/** The song that is playing, as serverId to songId. */
val LocalNowPlaying = staticCompositionLocalOf<Pair<String?, String?>?> { null }
