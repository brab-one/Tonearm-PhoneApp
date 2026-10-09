package io.github.deadeyebarb.tonearm.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaEngineTest {
    private val choices = listOf("mp3_320", SongUri.RAW, "opus_128")

    @Test
    fun `a song keeps the quality it started with`() {
        // Wi-Fi went mid-song: the reopen still asks for the original file, not mobile data's MP3.
        assertEquals(SongUri.RAW, MediaEngine.qualityToOpen(SongUri.RAW, false, choices) { false })
        assertEquals("opus_128", MediaEngine.qualityToOpen("opus_128", true, choices) { false })
        // The download is the original file, so only that switches to it.
        assertEquals(SongUri.DOWNLOADED, MediaEngine.qualityToOpen(SongUri.RAW, true, choices) { false })
        assertEquals(SongUri.DOWNLOADED, MediaEngine.qualityToOpen(SongUri.DOWNLOADED, false, choices) { false })
    }

    @Test
    fun `a new song plays its download, else a file cached in full, else the network's quality`() {
        assertEquals(SongUri.DOWNLOADED, MediaEngine.qualityToOpen(null, true, choices) { true })
        assertEquals("mp3_320", MediaEngine.qualityToOpen(null, false, choices) { false })
        // The whole FLAC is there: it plays from the cache, even on mobile data.
        assertEquals(SongUri.RAW, MediaEngine.qualityToOpen(null, false, choices) { it == SongUri.RAW })
        // The network's own quality comes first when it's there too.
        assertEquals("mp3_320", MediaEngine.qualityToOpen(null, false, choices) { true })
    }

    @Test
    fun `a song partly cached at another quality plays at the network's`() {
        val length = mapOf(SongUri.RAW to 40_000_000L, "opus_128" to 4_000_000L)
        fun complete(cached: Map<String, Long>): (String) -> Boolean = { q -> length[q]?.let { cached[q] == it } == true }
        // Cache ahead got 2 MB of the FLAC on Wi-Fi: on mobile data the rest isn't fetched, the network's quality is.
        assertEquals("mp3_320", MediaEngine.qualityToOpen(null, false, choices, complete(mapOf(SongUri.RAW to 2_000_000L))))
        // The Opus heard to the end yesterday plays from the cache.
        assertEquals("opus_128", MediaEngine.qualityToOpen(null, false, choices, complete(mapOf(SongUri.RAW to 2_000_000L, "opus_128" to 4_000_000L))))
    }

    @Test
    fun `youtube streams are told apart by format, version and size`() {
        val opus = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=1760000000&itag=251&source=youtube&clen=3456789&lmt=1700000000000000&mime=audio%2Fwebm"
        val aac = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=1760000000&itag=140&source=youtube&clen=4567890&lmt=1700000000000001&mime=audio%2Fmp4"
        assertEquals("251-1700000000000000-3456789", MediaEngine.youtubeStreamOf(opus))
        assertEquals("140-1700000000000001-4567890", MediaEngine.youtubeStreamOf(aac))
        assertNull(MediaEngine.youtubeStreamOf("https://example.com/audio.webm"))
    }
}
