package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheAheadTest {
    /** A queue of [count] songs (no shuffle order of its own, so shuffle plays in queue order). */
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

    @Test
    fun `the next songs in play order`() {
        assertEquals(listOf(3, 4, 5), CacheAhead.upcoming(Queue(10), 2, Player.REPEAT_MODE_OFF, false, 3))
        // The end of the queue stops it, unless the queue repeats.
        assertEquals(listOf(9), CacheAhead.upcoming(Queue(10), 8, Player.REPEAT_MODE_OFF, false, 3))
        assertEquals(listOf(9, 0, 1), CacheAhead.upcoming(Queue(10), 8, Player.REPEAT_MODE_ALL, false, 3))
        // Repeat-one: cache what comes after it in the queue, not the same song again.
        assertEquals(listOf(5, 6), CacheAhead.upcoming(Queue(10), 4, Player.REPEAT_MODE_ONE, false, 2))
        // Short queues don't wrap onto the current song.
        assertEquals(listOf(1, 2), CacheAhead.upcoming(Queue(3), 0, Player.REPEAT_MODE_ALL, false, 5))
        assertEquals(emptyList<Int>(), CacheAhead.upcoming(Queue(0), 0, Player.REPEAT_MODE_OFF, false, 3))
    }
}
