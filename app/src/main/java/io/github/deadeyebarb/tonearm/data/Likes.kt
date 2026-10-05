package io.github.deadeyebarb.tonearm.data

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.deadeyebarb.tonearm.local.LocalMusic
import android.content.Context
import io.github.deadeyebarb.tonearm.integrations.IntegrationsService
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.likes.PendingLikes
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The like button. Library songs are starred on the server. A YouTube Music song you like is requested
 * in Lidarr (its album, not the artist's whole discography) and waits in [pending] until it's in the
 * library, where it then gets liked. Playing a song alone never requests anything.
 */
class Likes(
    context: Context,
    private val json: Json,
    private val scope: CoroutineScope,
    private val api: SubsonicApi,
    private val sessions: SessionManager,
    private val starred: StarredStore,
    private val integrations: IntegrationsService,
    private val requests: SongRequests,
    private val settings: SettingsRepository,
    private val messages: Messages,
) {
    val pending = PendingLikes(File(context.filesDir, "pending_likes.json"), json)
    private val phoneFile = File(context.filesDir, "phone_likes.json")
    private val _phone = MutableStateFlow(runCatching { json.decodeFromString(ListSerializer(String.serializer()), phoneFile.readText()).toSet() }.getOrDefault(emptySet()))
    /** Ids of liked songs stored on the phone (they aren't on the server to be starred there). */
    val phone: StateFlow<Set<String>> = _phone
    private val resolving = Mutex()

    fun isLiked(entry: QueueSong): Boolean = when {
        YouTubeMusic.isYouTube(entry.serverId) -> pending.isLiked(entry.song.id)
        LocalMusic.isLocal(entry.serverId) -> entry.song.id in _phone.value
        else -> starred.isStarred(entry.serverId, entry.song.id, entry.song.starred != null)
    }

    suspend fun set(entry: QueueSong, liked: Boolean) {
        if (LocalMusic.isLocal(entry.serverId)) {
            _phone.update { if (liked) it + entry.song.id else it - entry.song.id }
            phoneFile.writeText(json.encodeToString(ListSerializer(String.serializer()), _phone.value.toList()))
            return
        }
        if (!YouTubeMusic.isYouTube(entry.serverId)) {
            starred.set(entry.serverId, StarKind.SONG, entry.song.id, liked)
            return
        }
        val ref = entry.song.toTrackRef()
        if (!liked) {
            pending.remove(ref)
            return
        }
        pending.add(ref)
        // Maybe the library has it already (downloaded since it was found on YouTube Music).
        if (resolveNow().isNotEmpty()) return
        request(ref)
    }

    private fun request(ref: TrackRef) {
        if (!settings.state.value.requestLikes) return
        scope.launch {
            try {
                val (config, key) = integrations.requireLidarrOrNull() ?: return@launch
                val result = requests.request(config, key, ref)
                pending.setRequest(ref, result.message)
                messages.show(result.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Couldn't request ${ref.title}: ${e.userMessage()}")
            }
        }
    }

    /** Likes on the server the pending songs that have arrived; returns them. */
    suspend fun resolveNow(): List<Song> = resolving.withLock {
        if (pending.items.value.isEmpty()) return emptyList()
        val session = sessions.active.value ?: return emptyList()
        val found = runCatching { pending.resolve(api, session) }.getOrDefault(emptyList())
        found.forEach { starred.remember(session.id, it.id, true) }
        if (found.isNotEmpty()) {
            messages.show(if (found.size == 1) "${found[0].title} is in your library now" else "${found.size} liked songs are in your library now")
        }
        found
    }

    /** Checks for arrived songs now and then while the app runs. */
    fun start() {
        scope.launch {
            sessions.active.first { it != null }
            while (true) {
                runCatching { resolveNow() }
                delay(RESOLVE_EVERY)
            }
        }
    }

    companion object {
        private const val RESOLVE_EVERY = 20 * 60_000L

        fun Song.toTrackRef() = TrackRef(
            title = title,
            artist = artistLabel,
            album = album,
            duration = duration,
            youtubeId = id,
            coverUrl = coverArt,
        )
    }
}
