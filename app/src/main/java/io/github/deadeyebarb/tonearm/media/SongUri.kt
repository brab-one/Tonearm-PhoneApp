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

    /** [version] names the file on the server (format and size), so a replaced file isn't mixed with cached bytes of the old one. */
    fun build(serverId: String, songId: String, quality: String? = null, version: String? = null): Uri =
        Uri.Builder().scheme(SCHEME).authority("song").appendPath(serverId).appendPath(songId)
            .apply {
                if (quality != null) appendQueryParameter("q", quality)
                if (version != null) appendQueryParameter("v", version)
            }
            .build()

    data class Parts(val serverId: String, val songId: String, val quality: String?, val version: String? = null)

    fun parse(uri: Uri): Parts? {
        if (uri.scheme != SCHEME || uri.host != "song") return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        return Parts(segments[0], segments[1], uri.getQueryParameter("q"), uri.getQueryParameter("v"))
    }

    /** The file version of a song as the server describes it, e.g. "flac-31457280". */
    fun versionOf(suffix: String?, size: Long?): String? = size?.takeIf { it > 0 }?.let { "${suffix.orEmpty()}-$it" }

    /** Splits a quality like `mp3_320` into format and max bitrate; raw/downloaded map to the original file. */
    fun formatOf(quality: String?): Pair<String, Int> {
        if (quality == null || quality == RAW || quality == DOWNLOADED || quality == YOUTUBE) return RAW to 0
        val format = quality.substringBefore('_')
        return format to (quality.substringAfter('_', "0").toIntOrNull() ?: 0)
    }
}
