package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioQueueTest {
    /** A queue of [count] songs, as in CacheAheadTest. */
    private class Queue(private val count: Int) : Timeline() {
        override fun getWindowCount() = count
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(windowIndex, null, null, 0, 0, 0, true, false, null, 0, 1_000_000, windowIndex, windowIndex, 0)
        override fun getPeriodCount() = count
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(periodIndex, periodIndex, periodIndex, 1_000_000, 0)
        override fun getIndexOfPeriod(uid: Any) = uid as? Int ?: -1
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    private var now = 1_000_000L
    private val refills = RadioRefills { now }

    private fun left(current: Int, count: Int = 10, repeat: Int = Player.REPEAT_MODE_OFF) =
        RadioQueue.leftAfter(Queue(count), current, repeat, false)

    @Test
    fun `songs left are counted in play order, up to one more than the refill point`() {
        assertEquals(4, left(2))
        assertEquals(3, left(6))
        assertEquals(1, left(8))
        assertEquals(0, left(9))
        // Repeating the queue wraps around, so there's always enough.
        assertEquals(4, left(9, repeat = Player.REPEAT_MODE_ALL))
    }

    @Test
    fun `asks for more at three songs left, not at four`() {
        // Of ten songs, the sixth leaves four to go and the seventh three.
        assertNull(refills.begin(left(5), repeatOff = true))
        assertNotNull(refills.begin(left(6), repeatOff = true))
    }

    @Test
    fun `never asks with repeat on`() {
        assertNull(refills.begin(0, repeatOff = false))
    }

    @Test
    fun `one ask at a time`() {
        val ticket = refills.begin(2, repeatOff = true)!!
        assertNull(refills.begin(1, repeatOff = true))
        assertTrue(refills.finish(ticket, ok = true))
        assertNotNull(refills.begin(1, repeatOff = true))
    }

    @Test
    fun `after a failed ask it waits before asking again, longer each time`() {
        val first = refills.begin(1, repeatOff = true)!!
        refills.finish(first, ok = false)
        assertEquals(10_000, refills.retryInMs())
        now += 9_999
        assertNull(refills.begin(1, repeatOff = true))
        now += 1
        val second = refills.begin(1, repeatOff = true)!!
        refills.finish(second, ok = false)
        assertEquals(20_000, refills.retryInMs())
        now += 19_999
        assertNull(refills.begin(1, repeatOff = true))
        now += 1
        val third = refills.begin(1, repeatOff = true)!!
        // Songs again: no more waiting.
        refills.finish(third, ok = true)
        assertEquals(0, refills.retryInMs())
        assertNotNull(refills.begin(1, repeatOff = true))
    }

    @Test
    fun `a late batch after the queue changed is dropped`() {
        val ticket = refills.begin(1, repeatOff = true)!!
        // A new queue (the radio stopped) or a new radio while the batch was on its way.
        refills.reset()
        assertFalse(refills.finish(ticket, ok = true))
        val restarted = refills.restart()
        assertFalse(refills.finish(ticket, ok = true))
        // The new radio's first ask is under way, so nothing else asks meanwhile.
        assertNull(refills.begin(0, repeatOff = true))
        assertTrue(refills.finish(restarted, ok = true))
    }

    @Test
    fun `a reset forgets earlier failures`() {
        val ticket = refills.begin(1, repeatOff = true)!!
        refills.finish(ticket, ok = false)
        assertNull(refills.begin(1, repeatOff = true))
        refills.reset()
        assertNotNull(refills.begin(1, repeatOff = true))
    }
}
