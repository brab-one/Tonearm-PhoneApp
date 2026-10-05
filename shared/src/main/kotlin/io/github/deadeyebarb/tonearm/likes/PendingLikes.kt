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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** A song liked before it was in the library (from YouTube Music), with what was asked of Lidarr. */
@Serializable
data class PendingLike(
    val ref: TrackRef,
    val likedAt: Long,
    val request: String? = null,
    /** The album Lidarr was asked for, to follow its download. */
    val requestedAlbum: String? = null,
    val requestedArtist: String? = null,
)

/** Pending likes as the apps share them: the likes, and when likes were taken back (or arrived). */
@Serializable
data class LikesDocument(val likes: List<PendingLike> = emptyList(), val removed: Map<String, Long> = emptyMap())

/**
 * Likes for songs the music server doesn't have yet. A like on such a song requests it in Lidarr and
 * waits here; once the song shows up in the library it's liked (starred) on the server and leaves.
 */
class PendingLikes(private val file: File, private val json: Json) {
    private val serializer = ListSerializer(PendingLike.serializer())
    private val removedFile = File(file.parentFile, file.nameWithoutExtension + "_removed.json")
    private val removedSerializer = MapSerializer(String.serializer(), Long.serializer())
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<PendingLike>> = _items.asStateFlow()
    private var removed: Map<String, Long> = runCatching { json.decodeFromString(removedSerializer, removedFile.readText()) }.getOrDefault(emptyMap())

    private fun load(): List<PendingLike> = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    /** What gets shared with the other devices. */
    @Synchronized
    fun document() = LikesDocument(_items.value, removed)

    /** Takes the merged state from a sync. */
    @Synchronized
    fun replace(document: LikesDocument) {
        removed = document.removed
        removedFile.parentFile?.mkdirs()
        removedFile.writeText(json.encodeToString(removedSerializer, removed))
        if (document.likes != _items.value) save(document.likes)
    }

    fun isLiked(ref: TrackRef): Boolean = _items.value.any { key(it.ref) == key(ref) }

    fun isLiked(youtubeId: String): Boolean = _items.value.any { it.ref.youtubeId == youtubeId }

    @Synchronized
    fun add(ref: TrackRef, request: String? = null) {
        if (isLiked(ref)) return
        save(_items.value + PendingLike(ref, System.currentTimeMillis(), request))
    }

    @Synchronized
    fun setRequest(ref: TrackRef, request: String, album: String? = null, artist: String? = null) =
        save(_items.value.map { if (key(it.ref) == key(ref)) it.copy(request = request, requestedAlbum = album, requestedArtist = artist) else it })

    /** Unlikes (or settles, once the song is in the library); other devices drop it too. */
    @Synchronized
    fun remove(ref: TrackRef) {
        removed = removed + (key(ref) to System.currentTimeMillis())
        removedFile.parentFile?.mkdirs()
        removedFile.writeText(json.encodeToString(removedSerializer, removed))
        save(_items.value.filterNot { key(it.ref) == key(ref) })
    }

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

    companion object {
        fun key(ref: TrackRef) = ref.youtubeId ?: Names.key(ref.artist, SongMatch.cleanTitle(ref.title))

        /** How long an unlike is remembered, so a device that was away doesn't bring the like back. */
        private const val FORGET_REMOVED_MS = 120L * 24 * 3_600_000

        /** Likes from both sides, minus the ones taken back after they were made. */
        fun merge(a: LikesDocument, b: LikesDocument, now: Long = System.currentTimeMillis()): LikesDocument {
            val removed = (a.removed.keys + b.removed.keys).associateWith { maxOf(a.removed[it] ?: 0, b.removed[it] ?: 0) }
                .filterValues { now - it < FORGET_REMOVED_MS }
            val likes = (a.likes + b.likes).groupBy { key(it.ref) }.map { (_, same) ->
                val newest = same.maxBy { it.likedAt }
                newest.copy(
                    request = newest.request ?: same.firstNotNullOfOrNull { it.request },
                    requestedAlbum = newest.requestedAlbum ?: same.firstNotNullOfOrNull { it.requestedAlbum },
                    requestedArtist = newest.requestedArtist ?: same.firstNotNullOfOrNull { it.requestedArtist },
                )
            }.filter { like -> (removed[key(like.ref)] ?: Long.MIN_VALUE) < like.likedAt }.sortedBy { it.likedAt }
            return LikesDocument(likes, removed)
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
