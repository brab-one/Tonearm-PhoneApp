package io.github.deadeyebarb.tonearm.mixes

import io.github.deadeyebarb.tonearm.integrations.NextSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPolicyTest {
    private fun song(id: String, artist: String?) = NextSong(Song(id, title = id, artist = artist), youtube = false)
    private fun ids(songs: List<NextSong>) = songs.joinToString(" ") { it.song.id }

    @Test
    fun `asks with three songs left, not four`() {
        assertTrue(RadioPolicy.wantsMore(left = 3, repeatOff = true, busy = false, failures = 0, msSinceFailure = 0))
        assertTrue(RadioPolicy.wantsMore(left = 0, repeatOff = true, busy = false, failures = 0, msSinceFailure = 0))
        assertFalse(RadioPolicy.wantsMore(left = 4, repeatOff = true, busy = false, failures = 0, msSinceFailure = 0))
    }

    @Test
    fun `never with repeat on or while one request runs`() {
        assertFalse(RadioPolicy.wantsMore(left = 1, repeatOff = false, busy = false, failures = 0, msSinceFailure = 0))
        assertFalse(RadioPolicy.wantsMore(left = 1, repeatOff = true, busy = true, failures = 0, msSinceFailure = 0))
    }

    @Test
    fun `after failures it waits 10, 20, 40 and then 60 seconds`() {
        assertEquals(listOf(0L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (0..5).map(RadioPolicy::retryDelayMs))
        assertFalse(RadioPolicy.wantsMore(left = 2, repeatOff = true, busy = false, failures = 1, msSinceFailure = 9_999))
        assertTrue(RadioPolicy.wantsMore(left = 2, repeatOff = true, busy = false, failures = 1, msSinceFailure = 10_000))
        assertFalse(RadioPolicy.wantsMore(left = 2, repeatOff = true, busy = false, failures = 3, msSinceFailure = 30_000))
        assertTrue(RadioPolicy.wantsMore(left = 2, repeatOff = true, busy = false, failures = 9, msSinceFailure = 60_000))
    }

    @Test
    fun `exclude keeps the newest 500 keys in order`() {
        val keys = (1..700).map { "lib/$it" }
        val sent = RadioPolicy.exclude(keys)
        assertEquals(500, sent.size)
        assertEquals("lib/201", sent.first())
        assertEquals("lib/700", sent.last())
        assertEquals(listOf("lib/1"), RadioPolicy.exclude(listOf("lib/1")))
    }

    @Test
    fun `spread keeps one artist three places apart when it can`() {
        val songs = listOf(song("a1", "A"), song("a2", "A"), song("b1", "B"), song("c1", "C"), song("a3", "A feat. D"), song("d1", "D"))
        assertEquals("a1 b1 c1 a2 d1 a3", ids(RadioPolicy.spread(songs)))
        // Nothing repeats: the order stays.
        val distinct = listOf(song("a1", "A"), song("b1", "B"), song("c1", "C"))
        assertEquals(distinct, RadioPolicy.spread(distinct))
    }

    @Test
    fun `spread keeps the order when there's no choice`() {
        val one = listOf(song("a1", "A"), song("a2", "A"), song("a3", "The A"), song("b1", "B"))
        assertEquals("a1 b1 a2 a3", ids(RadioPolicy.spread(one)))
        // Songs without an artist don't count as one artist.
        val unknown = listOf(song("x1", null), song("x2", ""), song("x3", null))
        assertEquals(unknown, RadioPolicy.spread(unknown))
        assertEquals("a1 b1 a2", ids(RadioPolicy.spread(listOf(song("a1", "A"), song("a2", "A"), song("b1", "B")), gap = 2)))
    }
}
