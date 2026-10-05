package io.github.deadeyebarb.tonearm.media

import android.net.Uri

/**
 * Playback URIs look like `tonearm://song/<server>/<song>?q=<quality>`. They never contain
 * credentials; [SubsonicDataSource] turns them into authenticated stream URLs at load time,
 * so a saved queue keeps working after a password change.
 */
object SongUri {
    const val SCHEME = "tonearm"
    const val RAW = "raw"
    /** Marks a request that should be served from the download cache. */
    const val DOWNLOADED = "dl"
    /** YouTube Music's own audio (no transcoding choices). */
    const val YOUTUBE = "yt"

    fun build(serverId: String, songId: String, quality: String? = null): Uri =
        Uri.Builder().scheme(SCHEME).authority("song").appendPath(serverId).appendPath(songId)
            .apply { if (quality != null) appendQueryParameter("q", quality) }
            .build()

    data class Parts(val serverId: String, val songId: String, val quality: String?)

    fun parse(uri: Uri): Parts? {
        if (uri.scheme != SCHEME || uri.host != "song") return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        return Parts(segments[0], segments[1], uri.getQueryParameter("q"))
    }

    /** Splits a quality like `mp3_320` into format and max bitrate; raw/downloaded map to the original file. */
    fun formatOf(quality: String?): Pair<String, Int> {
        if (quality == null || quality == RAW || quality == DOWNLOADED || quality == YOUTUBE) return RAW to 0
        val format = quality.substringBefore('_')
        return format to (quality.substringAfter('_', "0").toIntOrNull() ?: 0)
    }
}
