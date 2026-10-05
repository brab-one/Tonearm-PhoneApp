package io.github.deadeyebarb.tonearm.likes

import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A song liked before it was in the library (from YouTube Music), with what was asked of Lidarr. */
@Serializable
data class PendingLike(val ref: TrackRef, val likedAt: Long, val request: String? = null)

/**
 * Likes for songs the music server doesn't have yet. A like on such a song requests it in Lidarr and
 * waits here; once the song shows up in the library it's liked (starred) on the server and leaves.
 */
class PendingLikes(private val file: File, private val json: Json) {
    private val serializer = ListSerializer(PendingLike.serializer())
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<PendingLike>> = _items.asStateFlow()

    private fun load(): List<PendingLike> = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun key(ref: TrackRef) = ref.youtubeId ?: Names.key(ref.artist, SongMatch.cleanTitle(ref.title))

    fun isLiked(ref: TrackRef): Boolean = _items.value.any { key(it.ref) == key(ref) }

    fun isLiked(youtubeId: String): Boolean = _items.value.any { it.ref.youtubeId == youtubeId }

    @Synchronized
    fun add(ref: TrackRef, request: String? = null) {
        if (isLiked(ref)) return
        save(_items.value + PendingLike(ref, System.currentTimeMillis(), request))
    }

    @Synchronized
    fun setRequest(ref: TrackRef, request: String) = save(_items.value.map { if (key(it.ref) == key(ref)) it.copy(request = request) else it })

    @Synchronized
    fun remove(ref: TrackRef) = save(_items.value.filterNot { key(it.ref) == key(ref) })

    private fun save(items: List<PendingLike>) {
        _items.value = items
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, items))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Stars the pending songs that are in the library now; returns them. */
    suspend fun resolve(api: SubsonicApi, session: ServerSession): List<Song> {
        val found = mutableListOf<Song>()
        for (like in _items.value) {
            val song = runCatching { api.findSong(like.ref, session) }.getOrNull() ?: continue
            api.setStarred(session, io.github.deadeyebarb.tonearm.subsonic.StarKind.SONG, song.id, true)
            remove(like.ref)
            found += song
        }
        return found
    }
}

/** The library song for [ref], if the server has it. */
suspend fun SubsonicApi.findSong(ref: TrackRef, session: ServerSession? = null): Song? {
    val title = SongMatch.cleanTitle(ref.title)
    val artist = SongMatch.primaryArtist(SongMatch.cleanArtist(ref.artist))
    for (query in listOf(title, "$artist $title").distinct()) {
        val hits = search(query, artistCount = 0, albumCount = 0, songCount = 40, session = session).song
        SongMatch.best(hits, ref)?.let { return it }
    }
    return null
}
