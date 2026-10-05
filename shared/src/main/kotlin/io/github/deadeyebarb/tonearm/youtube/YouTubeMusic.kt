package io.github.deadeyebarb.tonearm.youtube

import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Where a YouTube Music song's audio is, and the User-Agent its URL was issued for. */
data class AudioSource(val url: String, val userAgent: String, val expiresAt: Long)

/**
 * Songs that aren't on your server, from YouTube Music (through NewPipeExtractor). They play in
 * the queue like library songs, with [SOURCE_ID] in place of a server id and the video id as song id.
 */
class YouTubeMusic(private val client: OkHttpClient) {
    @Volatile private var initialized = false
    private val sources = ConcurrentHashMap<String, AudioSource>()

    private fun init() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) NewPipe.init(NewPipeDownloader(client))
            initialized = true
        }
    }

    /** YouTube Music's "Songs" results for [query]. */
    suspend fun searchSongs(query: String, limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        init()
        val search = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        search.fetchPage()
        search.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toSong).distinctBy { it.id }.take(limit)
    }

    /** Songs by [artist], keeping the hits credited to that artist when there are any. */
    suspend fun artistSongs(artist: String, limit: Int = 25): List<Song> {
        val hits = searchSongs(artist, 40)
        val key = Names.normalize(artist)
        return hits.filter { Names.normalize(it.artist.orEmpty()).contains(key) }.ifEmpty { hits }.take(limit)
    }

    /** The audio for a song, resolved on first use and reused until shortly before it expires. Blocking. */
    fun audio(videoId: String): AudioSource {
        sources[videoId]?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let { return it }
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
        val stream = pickAudio(info.audioStreams) ?: throw IOException("YouTube Music has no playable audio for this song")
        return AudioSource(stream.content, userAgentFor(stream.content), expiryOf(stream.content)).also { sources[videoId] = it }
    }

    /** Forgets a resolved URL that stopped working (YouTube answered 403), so the next open resolves again. */
    fun invalidate(videoId: String) {
        sources.remove(videoId)
    }

    companion object {
        /** Stands in for a server id on songs that come from YouTube Music. */
        const val SOURCE_ID = "ytmusic"

        fun isYouTube(serverId: String?) = serverId == SOURCE_ID

        /** The best progressive audio stream: highest bitrate, Opus over AAC at the same rate. */
        fun pickAudio(streams: List<AudioStream>): AudioStream? =
            streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .maxWithOrNull(compareBy<AudioStream> { it.averageBitrate }.thenBy { it.format == MediaFormat.WEBMA_OPUS })

        /** googlevideo URLs are tied to the client they were issued for; fetch them as that client. */
        fun userAgentFor(url: String): String = when {
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            else -> NewPipeDownloader.USER_AGENT
        }

        /** The URL's own `expire` time, less a margin; an hour when it has none. */
        fun expiryOf(url: String, now: Long = System.currentTimeMillis()): Long {
            val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            return if (expire != null) expire * 1000 - 10 * 60_000 else now + 60 * 60_000
        }

        fun videoId(url: String): String? =
            url.toHttpUrlOrNull()?.queryParameter("v") ?: Regex("(?:v=|youtu\\.be/)([\\w-]{11})").find(url)?.groupValues?.get(1)

        fun toSong(item: StreamInfoItem): Song? {
            val id = videoId(item.url) ?: return null
            return Song(
                id = id,
                title = item.name,
                artist = item.uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() },
                duration = item.duration.toInt().takeIf { it > 0 },
                coverArt = bestThumbnail(item.thumbnails),
            )
        }

        /** The largest thumbnail; YouTube Music's square covers are re-requested at 544 px. */
        fun bestThumbnail(images: List<Image>): String? {
            val url = images.maxByOrNull { it.width * it.height }?.url ?: return null
            return url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        }

        /** Hosts YouTube Music serves covers from; the only ones the artwork provider fetches for it. */
        fun isThumbnailHost(host: String?): Boolean =
            host != null && listOf("ytimg.com", "googleusercontent.com", "ggpht.com").any { host == it || host.endsWith(".$it") }
    }
}
