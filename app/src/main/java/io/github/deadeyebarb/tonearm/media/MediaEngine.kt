package io.github.deadeyebarb.tonearm.media

import io.github.deadeyebarb.tonearm.local.LocalRouting
import io.github.deadeyebarb.tonearm.local.LocalMusic
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.data.StreamQuality
import io.github.deadeyebarb.tonearm.net.NetworkMonitor
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import okhttp3.OkHttpClient
import java.io.File

/**
 * Owns the two media caches and the data source chain the player reads through:
 *
 * ```
 * quality resolver ─▶ download cache (read-only) ─▶ stream cache (LRU) ─▶ SubsonicDataSource (mTLS, or YouTube Music)
 * ```
 *
 * The resolver stamps each request with the quality to fetch (original, or a transcode on
 * metered networks), `dl` when the song has been downloaded so it's served offline, or `yt`.
 * A song keeps the quality it started with (see [keepPinned]).
 */
class MediaEngine(
    context: Context,
    sessions: SessionManager,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val youtube: YouTubeMusic,
    /** For YouTube Music audio: no User-Agent override, no client certificate. */
    youtubeClient: OkHttpClient,
) {
    val databaseProvider = StandaloneDatabaseProvider(context)

    val downloadCache = SimpleCache(File(context.filesDir, "media/downloads"), NoOpCacheEvictor(), databaseProvider)

    val streamCache = SimpleCache(
        File(context.cacheDir, "media/stream"),
        DynamicLruEvictor { settings.state.value.streamCacheMb * 1024L * 1024L },
        databaseProvider,
    )

    /** Installed by the download repository; must be cheap and thread-safe. */
    @Volatile
    var isDownloaded: (serverId: String, songId: String) -> Boolean = { _, _ -> false }

    /**
     * The quality each recent song was first opened at. Every later open of it (a retry after Wi-Fi went,
     * a seek past what's buffered) gets the same, so the bytes carry on from the same file.
     */
    private val pinned = object : LinkedHashMap<String, String>(MAX_PINNED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_PINNED
    }

    val subsonicUpstream: DataSource.Factory = SubsonicDataSource.Factory(sessions, youtube, youtubeClient)

    private val streamLayer = CacheDataSource.Factory()
        .setCache(streamCache)
        .setCacheKeyFactory { spec -> streamCacheKey(spec) }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setUpstreamDataSourceFactory(subsonicUpstream)

    val playbackDataSourceFactory: DataSource.Factory = LocalRouting(context, ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setCacheKeyFactory { spec -> downloadCacheKey(spec) }
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setUpstreamDataSourceFactory(streamLayer),
    ) { spec -> stampQuality(spec) })

    /**
     * Fills the stream cache with a song's audio, at the quality playback will open it at, so it
     * plays from the cache later. Null for downloaded songs and URIs that aren't songs. Blocking: a
     * YouTube Music song may be looked up.
     */
    fun cacheWriter(uri: Uri): CacheWriter? {
        val spec = stampQuality(DataSpec(uri))
        val parts = SongUri.parse(spec.uri) ?: return null
        // Songs on the phone are there already.
        if (parts.quality == SongUri.DOWNLOADED || LocalMusic.isLocal(parts.serverId)) return null
        return CacheWriter(streamLayer.createDataSource(), spec, null, null)
    }

    fun currentQuality(): StreamQuality = settings.state.value.let { if (network.isMetered()) it.mobileQuality else it.wifiQuality }

    /** Whether a song plays without a connection: it's on the phone, downloaded or cached in full. Blocking. */
    fun playsOffline(uri: Uri): Boolean {
        val parts = SongUri.parse(uri) ?: return false
        if (LocalMusic.isLocal(parts.serverId) || isDownloaded(parts.serverId, parts.songId)) return true
        val s = settings.state.value
        val qualities = if (YouTubeMusic.isYouTube(parts.serverId)) listOf(SongUri.YOUTUBE) else listOf(SongUri.RAW, qualityOf(s.wifiQuality), qualityOf(s.mobileQuality))
        return qualities.any { quality ->
            val key = streamKey(parts.serverId, parts.songId, quality, parts.version)
            val length = ContentMetadata.getContentLength(streamCache.getContentMetadata(key))
            length != C.LENGTH_UNSET.toLong() && streamCache.isCached(key, 0, length)
        }
    }

    /** Forgets the quality of every song but [keep] (the one playing and the next), so a later play picks afresh. */
    fun keepPinned(keep: Collection<Uri>) {
        val keys = keep.mapNotNull(SongUri::parse).map { pinKey(it) }.toSet()
        synchronized(pinned) { pinned.keys.retainAll(keys) }
    }

    private fun stampQuality(spec: DataSpec): DataSpec {
        val parts = SongUri.parse(spec.uri) ?: return spec
        if (parts.quality != null) return spec
        val quality = if (YouTubeMusic.isYouTube(parts.serverId)) {
            checkYouTubeStream(parts.songId)
            SongUri.YOUTUBE
        } else {
            pinnedQuality(parts)
        }
        return spec.withUri(SongUri.build(parts.serverId, parts.songId, quality, parts.version))
    }

    private fun pinnedQuality(parts: SongUri.Parts): String {
        val downloaded = isDownloaded(parts.serverId, parts.songId)
        val s = settings.state.value
        val choices = listOf(qualityOf(currentQuality()), SongUri.RAW, qualityOf(s.wifiQuality), qualityOf(s.mobileQuality)).distinct()
        val key = pinKey(parts)
        return synchronized(pinned) {
            qualityToOpen(pinned[key], downloaded, choices) { quality ->
                streamCache.getCachedBytes(streamKey(parts.serverId, parts.songId, quality, parts.version), 0, C.LENGTH_UNSET.toLong())
            }.also { pinned[key] = it }
        }
    }

    private fun qualityOf(quality: StreamQuality): String =
        if (quality == StreamQuality.ORIGINAL) SongUri.RAW else "${settings.state.value.transcodeFormat.param}_${quality.maxBitRate}"

    private fun pinKey(parts: SongUri.Parts) = "${parts.serverId}/${parts.songId}/${parts.version}"

    /**
     * YouTube Music songs are cached under one key, whichever of the video's streams the bytes came from.
     * A song only partly cached is checked against the stream it would carry on with, and starts over when
     * that's another one (another format, a re-encode) instead of mixing the two. Songs cached in full
     * aren't looked up, so they still play offline.
     */
    private fun checkYouTubeStream(videoId: String) {
        val key = streamKey(YouTubeMusic.SOURCE_ID, videoId, SongUri.YOUTUBE, null)
        val metadata = streamCache.getContentMetadata(key)
        val length = ContentMetadata.getContentLength(metadata)
        if (length != C.LENGTH_UNSET.toLong() && streamCache.isCached(key, 0, length)) return
        // Looked up once and kept: opening the song right after needs it anyway.
        val stream = runCatching { youtubeStreamOf(youtube.audio(videoId).url) }.getOrNull() ?: return
        val cached = metadata.get(YOUTUBE_STREAM, null as String?)
        if (stream == cached) return
        runCatching {
            if (cached != null || streamCache.getCachedBytes(key, 0, C.LENGTH_UNSET.toLong()) > 0) streamCache.removeResource(key)
            streamCache.applyContentMetadataMutations(key, ContentMetadataMutations().set(YOUTUBE_STREAM, stream))
        }
    }

    private fun downloadCacheKey(spec: DataSpec): String {
        val parts = SongUri.parse(spec.uri)
        return if (parts != null && parts.quality == SongUri.DOWNLOADED) {
            downloadKey(parts.serverId, parts.songId)
        } else {
            "none:${spec.uri}"
        }
    }

    private fun streamCacheKey(spec: DataSpec): String {
        val parts = SongUri.parse(spec.uri) ?: return spec.key ?: spec.uri.toString()
        return streamKey(parts.serverId, parts.songId, parts.quality, parts.version)
    }

    /** A file upgraded on the server (say MP3 to FLAC under the same id) gets a fresh cache entry through its [version]. */
    private fun streamKey(serverId: String, songId: String, quality: String?, version: String?): String {
        val q = quality.takeUnless { it == SongUri.DOWNLOADED } ?: SongUri.RAW
        return "stream/$serverId/$songId/$q" + (version?.let { "/$it" } ?: "")
    }

    fun downloadKey(serverId: String, songId: String) = "dl/$serverId/$songId"

    fun clearStreamCache() {
        for (key in streamCache.keys.toList()) streamCache.removeResource(key)
    }

    companion object {
        /** Songs whose quality is kept: the queue's current and next ones, with room for those cached ahead. */
        private const val MAX_PINNED = 16
        private const val YOUTUBE_STREAM = ContentMetadata.KEY_CUSTOM_PREFIX + "tonearm_yt_stream"

        /**
         * The quality to open a song at: the one it's [pinned] to (the original file switches to its
         * download, the same bytes), else its download, else whichever of [choices] has the most cached
         * already. The first choice, the current network's, wins when none has any.
         */
        fun qualityToOpen(pinned: String?, downloaded: Boolean, choices: List<String>, cachedBytes: (String) -> Long): String = when {
            pinned == SongUri.RAW && downloaded -> SongUri.DOWNLOADED
            pinned != null -> pinned
            downloaded -> SongUri.DOWNLOADED
            else -> choices.maxBy(cachedBytes)
        }

        /** Which of a video's streams a googlevideo URL serves: format, file version and size. Null if it doesn't say. */
        fun youtubeStreamOf(url: String): String? {
            fun param(name: String) = Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)
            val itag = param("itag") ?: return null
            return "$itag-${param("lmt").orEmpty()}-${param("clen").orEmpty()}"
        }
    }
}
