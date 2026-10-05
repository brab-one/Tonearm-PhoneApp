package io.github.deadeyebarb.tonearm.media

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.deadeyebarb.tonearm.net.USER_AGENT
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Opens `tonearm://song/...` URIs through the owning server's OkHttp client — the one that
 * carries the mTLS client certificate and any pinned CA. Songs from YouTube Music resolve to
 * their audio URL here. Other URIs go through the shared factory.
 */
class SubsonicDataSource private constructor(
    private val sessions: SessionManager,
    private val youtube: YouTubeMusic,
    private val youtubeClient: OkHttpClient,
) : DataSource {

    class Factory(private val sessions: SessionManager, private val youtube: YouTubeMusic, private val youtubeClient: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = SubsonicDataSource(sessions, youtube, youtubeClient)
    }

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val parts = SongUri.parse(dataSpec.uri)
        if (parts != null && YouTubeMusic.isYouTube(parts.serverId)) return openYouTube(dataSpec, parts.songId, retry = true)
        val (callFactory, uri) = resolve(dataSpec.uri)
        val source = OkHttpDataSource.Factory(callFactory).setUserAgent(USER_AGENT).createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(dataSpec.buildUpon().setUri(uri).build())
    }

    private fun openYouTube(dataSpec: DataSpec, videoId: String, retry: Boolean): Long {
        val audio = try {
            youtube.audio(videoId)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("YouTube Music: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val source = OkHttpDataSource.Factory(youtubeClient).setUserAgent(audio.userAgent).createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        return try {
            source.open(dataSpec.buildUpon().setUri(audio.url.toUri()).build())
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // The URL went stale (or was issued for another client): resolve it once more.
            if (!retry || e.responseCode != 403) throw e
            source.close()
            youtube.invalidate(videoId)
            openYouTube(dataSpec, videoId, retry = false)
        }
    }

    private fun resolve(uri: Uri): Pair<Call.Factory, Uri> {
        val parts = SongUri.parse(uri) ?: return sessions.callFactory to uri
        val session = sessions.sessionBlocking(parts.serverId)
            ?: throw IOException("The server for this song was removed")
        val (format, maxBitRate) = SongUri.formatOf(parts.quality)
        return session.client to session.streamUrl(parts.songId, format, maxBitRate).toString().toUri()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate) { "read() before open()" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }
}
