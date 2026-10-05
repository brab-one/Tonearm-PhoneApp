package io.github.deadeyebarb.tonearm.media

import java.util.concurrent.ConcurrentHashMap

/**
 * File sizes the server reported for songs in the queue. Some reverse proxies stream without a
 * Content-Length; for the original file the size is known anyway, and with it FLAC stays seekable.
 */
object SongSizes {
    private val sizes = ConcurrentHashMap<String, Long>()

    fun remember(serverId: String, songId: String, size: Long) {
        if (size > 0) sizes["$serverId/$songId"] = size
    }

    fun of(serverId: String, songId: String): Long? = sizes["$serverId/$songId"]
}
