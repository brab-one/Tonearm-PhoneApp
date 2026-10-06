package io.github.deadeyebarb.tonearm.youtube

import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** An artist on YouTube Music (a YouTube channel). */
data class YtArtist(val url: String, val name: String, val imageUrl: String?, val subscribers: Long? = null)

/** An album on YouTube Music (a playlist of its tracks). */
data class YtAlbum(val url: String, val title: String, val artist: String?, val imageUrl: String?, val trackCount: Int? = null)

data class YtArtistPage(
    val artist: YtArtist,
    val description: String?,
    val bannerUrl: String?,
    val topSongs: List<Song>,
    val albums: List<YtAlbum>,
)

data class YtAlbumPage(val album: YtAlbum, val songs: List<Song>)

/**
 * YouTube Music's artists and albums, for music you don't have yet: who an artist is, their popular
 * songs and their albums, each playable (from YouTube Music) and requestable in Lidarr.
 */
class YouTubeCatalog(private val youtube: YouTubeMusic) {

    suspend fun searchArtists(query: String, limit: Int = 8): List<YtArtist> = search(query, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS) {
        (it as? ChannelInfoItem)?.let { item ->
            YtArtist(item.url, item.name, YouTubeMusic.bestThumbnail(item.thumbnails), item.subscriberCount.takeIf { n -> n > 0 })
        }
    }.take(limit)

    suspend fun searchAlbums(query: String, limit: Int = 12): List<YtAlbum> = search(query, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS) {
        (it as? PlaylistInfoItem)?.toAlbum()
    }.take(limit)

    /** The YouTube Music artist best matching [name], if any. */
    /** The YouTube Music album for one known by name (e.g. a suggestion), or null. */
    suspend fun findAlbum(artist: String, title: String): YtAlbum? {
        val hits = searchAlbums("$artist $title", 8)
        fun same(a: String?, b: String) = a != null && Names.normalize(SongMatch.cleanTitle(a)) == Names.normalize(SongMatch.cleanTitle(b))
        return hits.firstOrNull { same(it.title, title) && same(it.artist, artist) } ?: hits.firstOrNull { same(it.title, title) }
    }

    suspend fun findArtist(name: String): YtArtist? =
        searchArtists(name, 5).firstOrNull { Names.normalize(it.name) == Names.normalize(name) }

    suspend fun artistPage(artist: YtArtist): YtArtistPage = coroutineScope {
        val songs = async { runCatching { youtube.artistSongs(artist.name, 20) }.getOrDefault(emptyList()) }
        // Artist channels rarely expose their releases tab; YouTube Music's album search does the job,
        // "<artist> albums" finding the studio albums first.
        val queries = listOf("${artist.name} albums", artist.name, "${artist.name} ep").map { query ->
            async { runCatching { searchAlbumPages(query) }.getOrDefault(emptyList()) }
        }
        val info = withContext(Dispatchers.IO) {
            youtube.ensureInitialized()
            runCatching { ChannelInfo.getInfo(ServiceList.YouTube, artist.url) }.getOrNull()
        }
        val key = Names.normalize(artist.name)
        val albums = queries.flatMap { it.await() }
            .filter { Names.normalize(it.artist.orEmpty()).let { a -> a == key || a.startsWith("$key ") } }
            .distinctBy { Names.normalize(it.title) }
        YtArtistPage(
            artist = artist.copy(imageUrl = info?.avatars?.let(YouTubeMusic::bestThumbnail) ?: artist.imageUrl),
            description = info?.description?.takeIf { it.isNotBlank() },
            bannerUrl = info?.banners?.maxByOrNull { it.width }?.url,
            topSongs = songs.await(),
            albums = albums.take(MAX_ALBUMS),
        )
    }

    private suspend fun searchAlbumPages(query: String, pages: Int = 2): List<YtAlbum> = withContext(Dispatchers.IO) {
        youtube.ensureInitialized()
        val search = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "")
        search.fetchPage()
        val items = search.initialPage.items.toMutableList()
        var page = search.initialPage.nextPage
        repeat(pages - 1) {
            val next = page ?: return@repeat
            val more = search.getPage(next)
            items += more.items
            page = more.nextPage
        }
        items.mapNotNull { (it as? PlaylistInfoItem)?.toAlbum() }
    }

    suspend fun albumPage(album: YtAlbum): YtAlbumPage = withContext(Dispatchers.IO) {
        youtube.ensureInitialized()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, album.url)
        val artist = album.artist ?: info.uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() }
        val songs = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            YouTubeMusic.toSong(item)?.let { song ->
                song.copy(album = album.title, artist = song.artist ?: artist, coverArt = album.imageUrl ?: song.coverArt)
            }
        }
        YtAlbumPage(album.copy(artist = artist, imageUrl = album.imageUrl ?: YouTubeMusic.bestThumbnail(info.thumbnails), trackCount = songs.size), songs)
    }

    private suspend fun <T> search(query: String, filter: String, map: (Any) -> T?): List<T> = withContext(Dispatchers.IO) {
        youtube.ensureInitialized()
        val search = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        search.fetchPage()
        search.initialPage.items.mapNotNull(map)
    }

    private companion object {
        const val MAX_ALBUMS = 40
    }

    private fun PlaylistInfoItem.toAlbum(fallbackArtist: String? = null) = YtAlbum(
        url = url,
        title = name,
        artist = uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() } ?: fallbackArtist,
        imageUrl = YouTubeMusic.bestThumbnail(thumbnails),
        trackCount = streamCount.toInt().takeIf { it > 0 },
    )
}
