package io.github.deadeyebarb.tonearm.local

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import io.github.deadeyebarb.tonearm.media.SongUri

/** Songs on the phone are read straight from storage; everything else goes through [upstream] (caches, server). */
class LocalRouting(context: Context, private val upstream: DataSource.Factory) : DataSource.Factory {
    private val storage = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = object : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val parts = SongUri.parse(dataSpec.uri)
            val (source, spec) = if (parts != null && LocalMusic.isLocal(parts.serverId)) {
                storage.createDataSource() to dataSpec.withUri(LocalMusic.uri(parts.songId))
            } else {
                upstream.createDataSource() to dataSpec
            }
            listeners.forEach(source::addTransferListener)
            delegate = source
            return source.open(spec)
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
}
