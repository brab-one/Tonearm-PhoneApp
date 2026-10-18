package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.YouTubeNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeFallbackTest {
    private val now = 1_760_000_000_000L
    private val album = LidarrAlbum(id = 7, title = "Dummy")

    @Test
    fun `the tag counts the minutes until the server tries youtube music, rounded up`() {
        assertEquals("YouTube in 12 min", YouTubeFallback.tag(album.copy(youtubeAt = now + 12 * 60_000), now))
        assertEquals("YouTube in 12 min", YouTubeFallback.tag(album.copy(youtubeAt = now + 11 * 60_000 + 1), now))
        assertEquals("YouTube in 1 min", YouTubeFallback.tag(album.copy(youtubeAt = now + 1_000), now))
        assertEquals("YouTube in 20 min", YouTubeFallback.tag(album.copy(youtubeAt = now + 20 * 60_000), now))
    }

    @Test
    fun `once that time has passed it's soon, and after a try without a match it says so`() {
        assertEquals("YouTube soon", YouTubeFallback.tag(album.copy(youtubeAt = now), now))
        assertEquals("YouTube soon", YouTubeFallback.tag(album.copy(youtubeAt = now - 5 * 60_000), now))
        assertEquals("No YouTube match", YouTubeFallback.tag(album.copy(youtubeTried = now - 60_000), now))
        assertEquals("No YouTube match", YouTubeFallback.tag(album.copy(youtubeAt = now + 60_000, youtubeTried = now - 60_000), now))
    }

    @Test
    fun `no tag without the server's fallback, nor while the album is downloading`() {
        assertNull(YouTubeFallback.tag(album, now))
        assertNull(YouTubeFallback.tag(album.copy(youtubeAt = now + 60_000), now, queued = true))
        assertNull(YouTubeFallback.tag(album.copy(youtubeAt = now - 60_000), now, queued = true))
    }

    @Test
    fun `youtube now's answer reads as what lidarr is getting, or the server's problem`() {
        assertEquals("Getting “Dummy (YouTube)” from YouTube Music", YouTubeFallback.message(YouTubeNow(grabbed = true, title = "Dummy (YouTube)"), "Dummy"))
        assertEquals("Getting “Dummy” from YouTube Music", YouTubeFallback.message(YouTubeNow(grabbed = true), "Dummy"))
        assertEquals("No YouTube Music match for “Dummy”", YouTubeFallback.message(YouTubeNow(problem = "No YouTube Music match for “Dummy”"), "Dummy"))
        assertEquals("No YouTube Music match for “Dummy”", YouTubeFallback.message(YouTubeNow(), "Dummy"))
    }
}
