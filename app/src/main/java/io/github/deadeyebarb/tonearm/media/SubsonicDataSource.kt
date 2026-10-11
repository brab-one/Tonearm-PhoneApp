package io.github.deadeyebarb.tonearm.media

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
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
    private var opened: DataSpec? = null
    /**
     * Bytes the response still owes, counted when it came without a Content-Length for a file whose size is known; unset
     * otherwise (OkHttp checks a Content-Length itself).
     */
    private var owed = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        owed = C.LENGTH_UNSET.toLong()
        opened = dataSpec
        val parts = SongUri.parse(dataSpec.uri)
        if (parts != null && YouTubeMusic.isYouTube(parts.serverId)) return openYouTube(dataSpec, parts.songId, retry = true)
        val (callFactory, uri) = resolve(dataSpec.uri)
        val source = OkHttpDataSource.Factory(callFactory).setUserAgent(USER_AGENT).createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        val length = source.open(dataSpec.buildUpon().setUri(uri).build())
        if (parts == null || source.responseHeaders.keys.any { it.equals("Content-Length", ignoreCase = true) }) return length
        // No Content-Length (a proxy re-chunked the response): the original file's size is known, from the version the
        // song was asked for at (a download's too), else from the queue.
        if (SongUri.formatOf(parts.quality).first != SongUri.RAW) return length
        val size = SongUri.sizeOf(parts.version) ?: SongSizes.of(parts.serverId, parts.songId) ?: return length
        val expected = SongSizes.expected(size, dataSpec.position, dataSpec.length) ?: return length
        // A response that ends before that was cut off: it mustn't pass for the whole file (a download, the cache). Asked
        // for part of the file (a download carrying on), OkHttp counts on the part's length and ends it early without a word.
        owed = expected
        return if (length == C.LENGTH_UNSET.toLong()) expected else length
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
        // googlevideo sends a whole-file request at about 32 KB/s, barely above what the song needs; asked for
        // a range it sends at full speed. ExoPlayer leaves the Range header out when reading from the start.
        val spec = dataSpec.buildUpon().setUri(audio.url.toUri()).apply {
            if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("Range" to "bytes=0-"))
        }.build()
        return try {
            source.open(spec)
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

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = checkNotNull(delegate) { "read() before open()" }.read(buffer, offset, length)
        if (owed == C.LENGTH_UNSET.toLong()) return read
        // Like OkHttp's own "unexpected end of stream": a lost connection, which the download and the player try again.
        owed = SongSizes.afterRead(owed, read) ?: throw HttpDataSource.HttpDataSourceException.createForIOException(
            IOException("The connection ended $owed bytes before the end of the song"),
            checkNotNull(opened), HttpDataSource.HttpDataSourceException.TYPE_READ,
        )
        return read
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            owed = C.LENGTH_UNSET.toLong()
        }
    }
}
