package io.github.deadeyebarb.tonearm.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test
    fun `parses LRC with repeated stamps and skips metadata`() {
        val lyrics = Lyrics.parse(
            """
            [ar:Someone]
            [ti:Song]
            [00:01.50]First line
            [00:10.00][00:30.25]Chorus
            [01:02.123]Last
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf(1500L, 10_000L, 30_250L, 62_123L), lyrics.lines.map { it.startMs })
        assertEquals(listOf("First line", "Chorus", "Chorus", "Last"), lyrics.lines.map { it.text })
    }

    @Test
    fun `finds the line being sung`() {
        val lyrics = Lyrics.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(-1, lyrics.indexAt(500))
        assertEquals(0, lyrics.indexAt(1000))
        assertEquals(1, lyrics.indexAt(8999))
        assertEquals(2, lyrics.indexAt(60_000))
    }

    @Test
    fun `plain text stays unsynced`() {
        val lyrics = Lyrics.parse("\nVerse one\n\nVerse two\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("Verse one", "", "Verse two"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.indexAt(1000))
    }
}
