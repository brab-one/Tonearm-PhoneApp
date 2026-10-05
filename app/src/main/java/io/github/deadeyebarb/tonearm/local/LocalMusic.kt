package io.github.deadeyebarb.tonearm.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Music files on the phone itself, from Android's media library. They play straight from storage,
 * next to the server's library, with [SOURCE_ID] in place of a server id and the MediaStore id as
 * song id. Covers are the album art Android keeps for each album.
 */
class LocalMusic(private val context: Context) {
    private val _songs = MutableStateFlow<List<Song>?>(null)
    /** Null until loaded (or without permission). */
    val songs: StateFlow<List<Song>?> = _songs.asStateFlow()
    private val lock = Mutex()
    private var observing = false

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Loads (or reloads) the songs; empty without permission. */
    suspend fun load(force: Boolean = false): List<Song> = lock.withLock {
        if (!hasPermission()) return emptyList()
        _songs.value?.takeIf { !force }?.let { return it }
        val songs = withContext(Dispatchers.IO) { query() }
        _songs.value = songs
        observe()
        songs
    }

    fun albums(songs: List<Song>): List<Album> = songs.groupBy { it.albumId }.mapNotNull { (id, tracks) ->
        val first = tracks.first()
        Album(
            id = id ?: return@mapNotNull null,
            name = first.album ?: "Unknown album",
            artist = albumArtists[id] ?: first.artist,
            coverArt = first.coverArt,
            songCount = tracks.size,
            duration = tracks.sumOf { it.duration ?: 0 },
            year = tracks.firstNotNullOfOrNull { it.year },
            song = tracks.sortedWith(compareBy<Song>({ it.discNumber ?: 1 }, { it.track ?: Int.MAX_VALUE }, { it.title })),
        )
    }.sortedWith(compareBy({ Names.normalize(it.artistLabel) }, { it.year ?: 0 }, { Names.normalize(it.name) }))

    fun search(query: String): List<Song> {
        val q = Names.normalize(query)
        if (q.isEmpty()) return emptyList()
        return _songs.value.orEmpty().filter { s -> listOfNotNull(s.title, s.artist, s.album).any { Names.normalize(it).contains(q) } }
    }

    private val albumArtists = mutableMapOf<String, String>()

    private fun query(): List<Song> {
        val columns = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.ALBUM_ARTIST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.BITRATE)
                add(MediaStore.Audio.Media.GENRE)
            }
        }
        val out = mutableListOf<Song>()
        albumArtists.clear()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns.toTypedArray(),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use { c ->
            fun col(name: String) = c.getColumnIndex(name)
            val id = col(MediaStore.Audio.Media._ID)
            val title = col(MediaStore.Audio.Media.TITLE)
            val artist = col(MediaStore.Audio.Media.ARTIST)
            val album = col(MediaStore.Audio.Media.ALBUM)
            val albumId = col(MediaStore.Audio.Media.ALBUM_ID)
            val track = col(MediaStore.Audio.Media.TRACK)
            val year = col(MediaStore.Audio.Media.YEAR)
            val duration = col(MediaStore.Audio.Media.DURATION)
            val mime = col(MediaStore.Audio.Media.MIME_TYPE)
            val size = col(MediaStore.Audio.Media.SIZE)
            val name = col(MediaStore.Audio.Media.DISPLAY_NAME)
            val added = col(MediaStore.Audio.Media.DATE_ADDED)
            val albumArtist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) col(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
            val bitrate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) col(MediaStore.Audio.Media.BITRATE) else -1
            val genre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) col(MediaStore.Audio.Media.GENRE) else -1
            fun text(i: Int) = if (i < 0) null else c.getString(i)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
            while (c.moveToNext()) {
                val trackNo = c.getInt(track)
                val albumKey = "$ALBUM_PREFIX${c.getLong(albumId)}"
                text(albumArtist)?.let { albumArtists[albumKey] = it }
                out += Song(
                    id = c.getLong(id).toString(),
                    title = text(title) ?: text(name)?.substringBeforeLast('.') ?: "Untitled",
                    artist = text(artist),
                    album = text(album),
                    albumId = albumKey,
                    coverArt = c.getLong(albumId).toString(),
                    track = (trackNo % 1000).takeIf { it > 0 },
                    discNumber = (trackNo / 1000).takeIf { it > 0 },
                    year = c.getInt(year).takeIf { it > 0 },
                    duration = (c.getLong(duration) / 1000).toInt().takeIf { it > 0 },
                    contentType = text(mime),
                    suffix = text(name)?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() },
                    size = c.getLong(size).takeIf { it > 0 },
                    bitRate = if (bitrate >= 0) (c.getLong(bitrate) / 1000).toInt().takeIf { it > 0 } else null,
                    genre = text(genre),
                    created = c.getLong(added).toString(),
                )
            }
        }
        return out
    }

    /** Reloads when files are added or removed. */
    private fun observe() {
        if (observing) return
        observing = true
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    _songs.value = null
                }
            },
        )
    }

    companion object {
        /** Stands in for a server id on songs stored on the phone. */
        const val SOURCE_ID = "local"
        const val ALBUM_PREFIX = "phone-album-"

        val PERMISSION: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        fun isLocal(serverId: String?) = serverId == SOURCE_ID

        fun uri(songId: String): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId.toLong())

        /** The album art Android extracted for an album (from the files' tags or a folder image). */
        fun coverUri(albumId: String): Uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId.toLong())
    }
}
