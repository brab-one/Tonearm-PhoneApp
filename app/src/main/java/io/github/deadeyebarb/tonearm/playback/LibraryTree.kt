package io.github.deadeyebarb.tonearm.playback

import io.github.deadeyebarb.tonearm.local.LocalMusic
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.R
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.media.SongExtras
import io.github.deadeyebarb.tonearm.media.SongUri
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic

/**
 * The browse tree for Android Auto, Android Automotive and other media browsers, plus the logic
 * that turns media IDs coming from controllers into playable items.
 *
 * Android Auto shows up to four browsable root children as tabs, so the root is
 * Home / Library / Favorites / Downloads. Folders of albums ask for a grid, songs for a list.
 */
class LibraryTree(private val c: AppContainer) {
    private val factory get() = c.mediaItems
    private val api get() = c.api

    fun root(): MediaItem = factory.folder(MediaIds.ROOT, "Tonearm")

    /** Hints for the root: list layout by default, and that search is supported. */
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putBoolean(SEARCH_SUPPORTED, true)
    }

    private fun icon(res: Int): Uri =
        Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).authority(c.app.packageName)
            .appendPath(c.app.resources.getResourceTypeName(res)).appendPath(c.app.resources.getResourceEntryName(res)).build()

    private fun tab(id: String, title: String, iconRes: Int, mediaType: Int) = factory.folder(id, title, artwork = icon(iconRes), mediaType = mediaType)

    /** A folder whose children are albums, shown as a grid of covers. */
    private fun albumFolder(id: String, title: String, iconRes: Int? = null) = factory.folder(
        id, title, artwork = iconRes?.let(::icon), mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        extras = gridExtras(),
    )

    private fun gridExtras() = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    }

    suspend fun children(parentId: String): List<MediaItem> {
        val session = c.sessions.awaitActive()
        val s = session.id
        return when (parentId) {
            MediaIds.ROOT -> listOf(
                tab(MediaIds.HOME, "Home", R.drawable.ic_car_home, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                tab(MediaIds.LIBRARY, "Library", R.drawable.ic_car_library, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                tab(MediaIds.STARRED, "Liked", R.drawable.ic_car_favorite, MediaMetadata.MEDIA_TYPE_PLAYLIST),
                tab(MediaIds.DOWNLOADS, "Downloads", R.drawable.ic_car_download, MediaMetadata.MEDIA_TYPE_PLAYLIST),
            )
            MediaIds.HOME -> listOf(
                factory.folder(
                    MediaIds.MIX, "Shuffle all", "Random songs from your library", artwork = icon(R.drawable.ic_car_shuffle),
                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true, browsable = false,
                ),
            ) + listOfNotNull(
                c.tonearmServer.server.value?.takeIf { it.history }?.let {
                    factory.folder(
                        MediaIds.DAILY, "Daily Discovery", "Fresh recommendations every day", artwork = icon(R.drawable.ic_car_new),
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true, browsable = false,
                    )
                },
                c.tonearmServer.server.value?.takeIf { it.history }?.let {
                    factory.folder(
                        MediaIds.HISTORY_MIX, "Your mix", "Similar to what you've been playing", artwork = icon(R.drawable.ic_car_explore),
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true, browsable = false,
                    )
                },
            ) + listOf(
                albumFolder(MediaIds.NEWEST, "Recently added", R.drawable.ic_car_new),
                albumFolder(MediaIds.RECENT, "Recently played", R.drawable.ic_car_history),
                albumFolder(MediaIds.FREQUENT, "Most played", R.drawable.ic_car_trending),
                albumFolder(MediaIds.RANDOM_ALBUMS, "Random picks", R.drawable.ic_car_explore),
            )
            MediaIds.LIBRARY -> listOf(
                factory.folder(MediaIds.ARTISTS, "Artists", artwork = icon(R.drawable.ic_car_artist), mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                albumFolder(MediaIds.ALBUMS, "Albums", R.drawable.ic_car_album),
                factory.folder(MediaIds.PLAYLISTS, "Playlists", artwork = icon(R.drawable.ic_car_playlist), mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                factory.folder(MediaIds.GENRES, "Genres", artwork = icon(R.drawable.ic_car_genre), mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
            ) + listOfNotNull(
                // Music stored on the phone, once Tonearm may see it.
                albumFolder(MediaIds.PHONE, "On this phone", R.drawable.ic_car_album).takeIf { c.local.hasPermission() },
            )
            MediaIds.NEWEST -> api.albumList(AlbumListType.NEWEST, 60, session = session).map { factory.album(s, it) }
            MediaIds.RECENT -> api.albumList(AlbumListType.RECENT, 60, session = session).map { factory.album(s, it) }
            MediaIds.FREQUENT -> api.albumList(AlbumListType.FREQUENT, 60, session = session).map { factory.album(s, it) }
            MediaIds.RANDOM_ALBUMS -> api.albumList(AlbumListType.RANDOM, 60, session = session).map { factory.album(s, it) }
            MediaIds.ALBUMS -> api.albumList(AlbumListType.BY_NAME, 500, session = session).map { factory.album(s, it) }
            MediaIds.PLAYLISTS -> api.playlists(session).map { factory.playlist(s, it) }
            MediaIds.ARTISTS -> api.artists(session).flatMap { it.artist }.map { factory.artist(s, it, gridExtras()) }
            MediaIds.GENRES -> api.genres(session).sortedBy { it.value.lowercase() }.map {
                factory.folder(
                    MediaIds.genre(s, it.value), it.value,
                    "${plural(it.albumCount, "album")} · ${plural(it.songCount, "song")}",
                    mediaType = MediaMetadata.MEDIA_TYPE_GENRE,
                )
            }
            MediaIds.STARRED, MediaIds.DOWNLOADS -> songsOf(parentId).map { factory.song(it, parentId) }
            MediaIds.PHONE -> c.local.albums(c.local.load()).map { factory.album(LocalMusic.SOURCE_ID, it) }
            else -> {
                val parsed = MediaIds.parse(parentId)
                when (parsed.type) {
                    MediaIds.ALBUM, MediaIds.PLAYLIST, MediaIds.GENRE -> songsOf(parentId).map { factory.song(it, parentId) }
                    MediaIds.ARTIST -> api.artist(parsed.id!!, session).album.sortedByDescending { it.year ?: 0 }.map { factory.album(s, it) }
                    else -> emptyList()
                }
            }
        }
    }

    /** All songs in a container media ID, in play order. */
    suspend fun songsOf(containerId: String): List<QueueSong> {
        when (containerId) {
            MediaIds.HISTORY_MIX -> return c.recommender.mix()
            MediaIds.DAILY -> return c.daily.ensureToday().entries
            MediaIds.MIX -> {
                val session = c.sessions.awaitActive()
                return api.randomSongs(200, session).map { QueueSong(session.id, it) }
            }
            MediaIds.STARRED -> {
                val session = c.sessions.awaitActive()
                return api.starred(session).song.map { QueueSong(session.id, it) }
            }
            MediaIds.DOWNLOADS -> return c.downloads.entries.value.values.filter { it.completed }.map { it.item }
                .sortedWith(compareBy({ it.song.album }, { it.song.discNumber }, { it.song.track }))
        }
        val parsed = MediaIds.parse(containerId)
        val serverId = parsed.serverId ?: return emptyList()
        if (LocalMusic.isLocal(serverId)) {
            val songs = c.local.load()
            return if (parsed.type == MediaIds.ALBUM) c.local.albums(songs).firstOrNull { it.id == parsed.id }?.song.orEmpty().map { QueueSong(serverId, it) } else emptyList()
        }
        val session = c.sessions.session(serverId) ?: return emptyList()
        return when (parsed.type) {
            MediaIds.ALBUM -> api.album(parsed.id!!, session).song
            MediaIds.PLAYLIST -> api.playlist(parsed.id!!, session).entry
            MediaIds.GENRE -> api.songsByGenre(parsed.id!!, 500, session = session)
            else -> emptyList()
        }.map { QueueSong(serverId, it) }
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == MediaIds.ROOT) return root()
        val parsed = MediaIds.parse(mediaId)
        if (parsed.type != MediaIds.SONG) return null
        if (LocalMusic.isLocal(parsed.serverId)) {
            return c.local.load().firstOrNull { it.id == parsed.id }?.let { factory.song(LocalMusic.SOURCE_ID, it, parsed.context) }
        }
        val session = c.sessions.session(parsed.serverId ?: return null) ?: return null
        return factory.song(session.id, api.song(parsed.id!!, session), parsed.context)
    }

    suspend fun search(query: String): List<MediaItem> {
        val session = c.sessions.awaitActive()
        val result = api.search(query, artistCount = 5, albumCount = 10, songCount = 50, session = session)
        val groups = Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, "Albums") }
        return result.album.map { factory.album(session.id, it, groups) } +
            result.song.map { factory.song(session.id, it) }
    }

    /** Fills in playback URIs (stripped by Media3 when items cross from a controller) and missing metadata. */
    suspend fun resolve(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { item ->
        val parsed = MediaIds.parse(item.mediaId)
        if (parsed.type != MediaIds.SONG || parsed.serverId == null || parsed.id == null) return@mapNotNull null
        if (item.mediaMetadata.extras?.getString(SongExtras.ID) != null) {
            val extras = item.mediaMetadata.extras
            val version = SongUri.versionOf(extras?.getString(SongExtras.SUFFIX), extras?.getLong(SongExtras.SIZE, -1)?.takeIf { it > 0 })
            item.buildUpon().setUri(SongUri.build(parsed.serverId, parsed.id, version = version)).build()
        } else {
            runCatching { item(item.mediaId) }.getOrNull()
        }
    }

    /**
     * A single song picked from a browsed album or playlist expands to the whole container;
     * "Shuffle all" and voice searches ("play X on Tonearm") are resolved here too.
     */
    suspend fun resolveForPlayback(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaItemsWithStartPosition {
        if (items.size == 1) {
            val item = items[0]
            val query = item.requestMetadata.searchQuery
            if (query != null && item.mediaId.isEmpty()) return voiceSearch(query)
            val parsed = MediaIds.parse(item.mediaId)
            val container = when {
                item.mediaId == MediaIds.MIX -> MediaIds.MIX
                item.mediaId == MediaIds.HISTORY_MIX -> MediaIds.HISTORY_MIX
                item.mediaId == MediaIds.DAILY -> MediaIds.DAILY
                parsed.type == MediaIds.ALBUM || parsed.type == MediaIds.PLAYLIST || parsed.type == MediaIds.GENRE -> item.mediaId
                parsed.type == MediaIds.SONG && parsed.context != null -> parsed.context
                else -> null
            }
            if (container != null) {
                val songs = songsOf(container)
                val index = songs.indexOfFirst { it.serverId == parsed.serverId && it.song.id == parsed.id }
                if (songs.isNotEmpty()) {
                    return MediaItemsWithStartPosition(
                        songs.map { factory.song(it, container) },
                        index.coerceAtLeast(0),
                        if (index >= 0) startPositionMs else 0,
                    )
                }
            }
        }
        return MediaItemsWithStartPosition(resolve(items), startIndex, startPositionMs)
    }

    private suspend fun voiceSearch(query: String): MediaItemsWithStartPosition {
        if (query.isBlank()) return MediaItemsWithStartPosition(songsOf(MediaIds.MIX).map { factory.song(it, MediaIds.MIX) }, 0, 0)
        val session = c.sessions.awaitActive()
        val result = api.search(query, artistCount = 1, albumCount = 1, songCount = 20, session = session)
        val album = result.album.firstOrNull()
        val songs = when {
            album != null && album.name.equals(query, ignoreCase = true) -> api.album(album.id, session).song
            result.song.isNotEmpty() -> result.song
            album != null -> api.album(album.id, session).song
            else -> emptyList()
        }
        if (songs.isEmpty() && c.settings.state.value.youtubeFallback) {
            // Not on the server: play it from YouTube Music (which also requests it in Lidarr).
            val youtube = runCatching { c.youtube.searchSongs(query, 20) }.getOrDefault(emptyList())
            return MediaItemsWithStartPosition(youtube.map { factory.song(YouTubeMusic.SOURCE_ID, it) }, 0, 0)
        }
        return MediaItemsWithStartPosition(songs.map { factory.song(session.id, it) }, 0, 0)
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private companion object {
        /** MediaBrowserCompat's root hint that makes Android Auto show its search button. */
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}
