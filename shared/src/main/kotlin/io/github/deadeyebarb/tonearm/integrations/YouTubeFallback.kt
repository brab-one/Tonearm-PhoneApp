package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.YouTubeNow

/** The Tonearm server getting wanted albums from YouTube Music when nothing else turns up, as the apps show it. */
object YouTubeFallback {
    /**
     * The tag on a wanted album: "YouTube in 12 min" until the server tries YouTube Music for it (minutes rounded up),
     * "YouTube soon" once that time has passed, "No YouTube match" when it tried and found nothing. Null when the server
     * doesn't try it, and while the album is [queued]: the server leaves those alone.
     */
    fun tag(album: LidarrAlbum, now: Long = System.currentTimeMillis(), queued: Boolean = false): String? {
        if (album.youtubeTried != null) return "No YouTube match"
        val at = album.youtubeAt ?: return null
        if (queued) return null
        val left = at - now
        return if (left > 0) "YouTube in ${(left + MINUTE_MS - 1) / MINUTE_MS} min" else "YouTube soon"
    }

    /** What "From YouTube now" came to, for a message: the release Lidarr grabbed, or why there was none. */
    fun message(result: YouTubeNow, album: String): String = when {
        result.grabbed -> "Getting “${result.title?.takeIf { it.isNotBlank() } ?: album}” from YouTube Music"
        else -> result.problem?.takeIf { it.isNotBlank() } ?: "No YouTube Music match for “$album”"
    }

    private const val MINUTE_MS = 60_000L
}
