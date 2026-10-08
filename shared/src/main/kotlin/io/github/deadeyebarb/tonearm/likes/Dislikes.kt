package io.github.deadeyebarb.tonearm.likes

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.connect.Disliked
import io.github.deadeyebarb.tonearm.connect.DislikedSong
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Songs the user disliked, and artists they said "not for me" to, kept on the Tonearm server so every app knows
 * them. Search puts them last, mixes leave them out, and the server's picks steer away from them.
 */
class Dislikes(
    private val client: ConnectClient,
    private val router: ConnectRouter,
    private val session: suspend () -> ServerSession?,
) {
    private val _state = MutableStateFlow(Disliked())
    val state: StateFlow<Disliked> = _state.asStateFlow()
    @Volatile private var songKeys: Set<String> = emptySet()
    @Volatile private var artistKeys: Set<String> = emptySet()
    /** Whose lists these are (server and user): another's are forgotten as soon as the session changes. */
    @Volatile private var owner: String? = null
    /** Goes up with every change made here, so a refresh that began before one doesn't undo it. */
    private val changes = AtomicLong()

    fun isDisliked(artist: String?, title: String): Boolean = songKey(artist.orEmpty(), title) in songKeys

    fun isArtistDisliked(artist: String?): Boolean = !artist.isNullOrBlank() && artistKey(artist) in artistKeys

    /** [items] with disliked songs last and songs by artists said no to just before them; the rest keep their order. */
    fun <T> demote(items: List<T>, artist: (T) -> String?, title: (T) -> String): List<T> =
        if (songKeys.isEmpty() && artistKeys.isEmpty()) items
        else items.map { it to if (isDisliked(artist(it), title(it))) 2 else if (isArtistDisliked(artist(it))) 1 else 0 }.sortedBy { it.second }.map { it.first }

    /**
     * Gets the list from the Tonearm server. Without a session, or with a server that keeps no dislikes, there are none;
     * when only the fetch fails, what's known stays.
     */
    suspend fun refresh() {
        val s = session()
        val who = s?.let(::ownerOf)
        switchTo(who)
        if (s == null) return
        if (router.refresh(s)?.dislikes != true) return update(who) { Disliked() }
        val seen = changes.get()
        runCatching { client.disliked(s) }.onSuccess { fresh -> update(who) { if (changes.get() == seen) fresh else it } }
    }

    /** Dislikes a song or takes that back: shown at once, then saved on the server. Undone and thrown when that fails. */
    suspend fun set(artist: String, title: String, album: String?, disliked: Boolean) {
        val s = session() ?: throw NoServerException()
        val who = ownerOf(s)
        switchTo(who)
        val key = songKey(artist, title)
        val before = _state.value.songs.firstOrNull { songKey(it.artist, it.title) == key }
        val now = DislikedSong(artist, title, album, System.currentTimeMillis()).takeIf { disliked }
        changes.incrementAndGet()
        update(who) { it.with(key, now) }
        try {
            if (router.refresh(s)?.dislikes != true) throw IOException("Dislikes are kept by the Tonearm server (1.5.4 or later) on your music server")
            client.dislike(s, artist, title, album, disliked)
        } catch (e: Exception) {
            // Only this change is undone; newer ones stay.
            update(who) { it.with(key, before) }
            throw e
        } finally {
            changes.incrementAndGet()
        }
    }

    private fun Disliked.with(key: String, song: DislikedSong?) = copy(songs = songs.filterNot { songKey(it.artist, it.title) == key } + listOfNotNull(song))

    private fun switchTo(who: String?) = synchronized(this) {
        if (who != owner) {
            owner = who
            publish(Disliked())
        }
    }

    /** Changes the lists when they're still [who]'s. */
    private fun update(who: String?, change: (Disliked) -> Disliked) = synchronized(this) {
        if (owner == who) publish(change(_state.value))
    }

    private fun publish(disliked: Disliked) {
        songKeys = disliked.songs.map { songKey(it.artist, it.title) }.toSet()
        artistKeys = disliked.artists.map(::artistKey).toSet()
        _state.value = disliked
    }

    private fun ownerOf(s: ServerSession) = s.config.baseUrl.trimEnd('/') + "|" + s.config.username

    companion object {
        /** A song by its first artist and its title without decorations, so the YouTube Music copy matches the library's. */
        fun songKey(artist: String, title: String) = Names.key(artistKey(artist), SongMatch.cleanTitle(title))

        private fun artistKey(artist: String) = Names.normalize(SongMatch.primaryArtist(SongMatch.cleanArtist(artist)))
    }
}
