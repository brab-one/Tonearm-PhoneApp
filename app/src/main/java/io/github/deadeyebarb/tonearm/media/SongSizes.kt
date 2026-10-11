package io.github.deadeyebarb.tonearm.media

import androidx.media3.common.C
import java.util.concurrent.ConcurrentHashMap

/**
 * File sizes the server reported for songs in the queue. Some reverse proxies stream without a
 * Content-Length; for the original file the size is known anyway, and with it FLAC stays seekable,
 * and a response cut off before the end isn't taken for the whole file.
 */
object SongSizes {
    private val sizes = ConcurrentHashMap<String, Long>()

    fun remember(serverId: String, songId: String, size: Long) {
        if (size > 0) sizes["$serverId/$songId"] = size
    }

    fun of(serverId: String, songId: String): Long? = sizes["$serverId/$songId"]

    /**
     * What a response without a Content-Length has to bring of a file of [size] read from [position]: the rest of it, or
     * [length] of it (unset: to the end). Null when nothing is left there to read.
     */
    fun expected(size: Long, position: Long, length: Long): Long? {
        val left = size - position
        if (left <= 0) return null
        return if (length == C.LENGTH_UNSET.toLong()) left else minOf(left, length)
    }

    /**
     * The bytes still [owed] after a read that brought [read] of them; null when the response ended
     * ([C.RESULT_END_OF_INPUT]) with bytes still owed, so it was cut off.
     */
    fun afterRead(owed: Long, read: Int): Long? = when {
        read != C.RESULT_END_OF_INPUT -> owed - read
        owed > 0 -> null
        else -> owed
    }
}
