package io.github.deadeyebarb.tonearm.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

class YouTubeMusicTest {
    private fun stream(itag: String, format: MediaFormat, kbps: Int, delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP) =
        AudioStream.Builder().setId(itag).setContent("https://rr1---sn.googlevideo.com/videoplayback?itag=$itag", true)
            .setMediaFormat(format).setDeliveryMethod(delivery).setAverageBitrate(kbps).build()

    @Test
    fun `the best progressive stream wins, opus on a tie`() {
        // What YouTube offered for a song when this was written.
        val streams = listOf(
            stream("139", MediaFormat.M4A, 48), stream("140", MediaFormat.M4A, 128),
            stream("249", MediaFormat.WEBMA_OPUS, 50), stream("251", MediaFormat.WEBMA_OPUS, 160),
            stream("999", MediaFormat.WEBMA_OPUS, 320, DeliveryMethod.DASH),
        )
        assertEquals("251", YouTubeMusic.pickAudio(streams)!!.id)
        assertEquals("b", YouTubeMusic.pickAudio(listOf(stream("a", MediaFormat.M4A, 128), stream("b", MediaFormat.WEBMA_OPUS, 128)))!!.id)
        assertNull(YouTubeMusic.pickAudio(emptyList()))
    }

    @Test
    fun `resolved urls are reused until shortly before they expire`() {
        val url = "https://rr1---sn.googlevideo.com/videoplayback?expire=1791240303&ei=x"
        assertEquals(1791240303L * 1000 - 10 * 60_000, YouTubeMusic.expiryOf(url))
        assertEquals(1_000L + 60 * 60_000, YouTubeMusic.expiryOf("https://example.com/a", now = 1_000L))
    }

    @Test
    fun `video ids and covers`() {
        assertEquals("Rgrt_8mXrK8", YouTubeMusic.videoId("https://music.youtube.com/watch?v=Rgrt_8mXrK8"))
        assertEquals("Rgrt_8mXrK8", YouTubeMusic.videoId("https://youtu.be/Rgrt_8mXrK8"))
        val thumbs = listOf(
            Image("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 60, 60, Image.ResolutionLevel.LOW),
            Image("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", 120, 120, Image.ResolutionLevel.LOW),
        )
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", YouTubeMusic.bestThumbnail(thumbs))
    }

    @Test
    fun `only youtube's image hosts are fetched for covers`() {
        assertTrue(YouTubeMusic.isThumbnailHost("lh3.googleusercontent.com"))
        assertTrue(YouTubeMusic.isThumbnailHost("i.ytimg.com"))
        assertTrue(YouTubeMusic.isThumbnailHost("yt3.ggpht.com"))
        assertFalse(YouTubeMusic.isThumbnailHost("evilgoogleusercontent.com"))
        assertFalse(YouTubeMusic.isThumbnailHost("192.168.0.10"))
        assertFalse(YouTubeMusic.isThumbnailHost(null))
    }
}
