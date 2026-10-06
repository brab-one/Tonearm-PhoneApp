package io.github.deadeyebarb.tonearm.media

import io.github.deadeyebarb.tonearm.local.LocalRouting
import io.github.deadeyebarb.tonearm.local.LocalMusic
import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
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
 */
class MediaEngine(
    context: Context,
    sessions: SessionManager,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    youtube: YouTubeMusic,
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
     * Fills the stream cache with a song's audio, at the quality playback would pick right now, so it
     * plays from the cache later. Null for downloaded songs and URIs that aren't songs.
     */
    fun cacheWriter(uri: Uri): CacheWriter? {
        val spec = stampQuality(DataSpec(uri))
        val parts = SongUri.parse(spec.uri) ?: return null
        // Songs on the phone are there already.
        if (parts.quality == SongUri.DOWNLOADED || LocalMusic.isLocal(parts.serverId)) return null
        return CacheWriter(streamLayer.createDataSource(), spec, null, null)
    }

    fun currentQuality(): StreamQuality = settings.state.value.let { if (network.isMetered()) it.mobileQuality else it.wifiQuality }

    private fun stampQuality(spec: DataSpec): DataSpec {
        val parts = SongUri.parse(spec.uri) ?: return spec
        if (parts.quality != null) return spec
        val quality = when {
            YouTubeMusic.isYouTube(parts.serverId) -> SongUri.YOUTUBE
            isDownloaded(parts.serverId, parts.songId) -> SongUri.DOWNLOADED
            else -> currentQuality().let { q ->
                if (q == StreamQuality.ORIGINAL) SongUri.RAW else "${settings.state.value.transcodeFormat.param}_${q.maxBitRate}"
            }
        }
        return spec.withUri(SongUri.build(parts.serverId, parts.songId, quality, parts.version))
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
        val quality = parts.quality.takeUnless { it == SongUri.DOWNLOADED } ?: SongUri.RAW
        // A file upgraded on the server (say MP3 to FLAC under the same id) gets a fresh cache entry.
        return "stream/${parts.serverId}/${parts.songId}/$quality" + (parts.version?.let { "/$it" } ?: "")
    }

    fun downloadKey(serverId: String, songId: String) = "dl/$serverId/$songId"

    fun clearStreamCache() {
        for (key in streamCache.keys.toList()) streamCache.removeResource(key)
    }
}
