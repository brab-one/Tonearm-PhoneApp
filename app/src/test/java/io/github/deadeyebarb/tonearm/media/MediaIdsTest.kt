package io.github.deadeyebarb.tonearm.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIdsTest {
    @Test
    fun `song ids round-trip, even with slashes and spaces`() {
        val context = MediaIds.album("srv", "al/1")
        val id = MediaIds.song("srv", "tr 1/b", context)
        val parsed = MediaIds.parse(id)
        assertEquals(MediaIds.SONG, parsed.type)
        assertEquals("srv", parsed.serverId)
        assertEquals("tr 1/b", parsed.id)
        assertEquals(context, parsed.context)
        assertEquals("al/1", MediaIds.parse(parsed.context!!).id)
    }

    @Test
    fun `root containers parse as their own type`() {
        assertEquals(MediaIds.STARRED, MediaIds.parse(MediaIds.STARRED).type)
    }

    @Test
    fun `quality strings map to stream parameters`() {
        assertEquals("raw" to 0, SongUri.formatOf(null))
        assertEquals("raw" to 0, SongUri.formatOf(SongUri.DOWNLOADED))
        assertEquals("opus" to 128, SongUri.formatOf("opus_128"))
    }
}
