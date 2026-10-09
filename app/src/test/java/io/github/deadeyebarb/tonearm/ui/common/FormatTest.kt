package io.github.deadeyebarb.tonearm.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `counts of one are singular`() {
        assertEquals("1 album", plural(1, "album"))
        assertEquals("2 songs", plural(2, "song"))
        assertEquals("0 tracks", plural(0, "track"))
    }

    @Test
    fun `last seen reads as a time ago, not a duration`() {
        assertEquals("just now", formatAgo(42))
        assertEquals("5 min ago", formatAgo(5 * 60 + 10))
        assertEquals("1 h ago", formatAgo(3723))
        assertEquals("1 day ago", formatAgo(26 * 3600))
        assertEquals("3 days ago", formatAgo(3 * 24 * 3600))
        assertEquals("just now", formatAgo(-5))
    }
}
