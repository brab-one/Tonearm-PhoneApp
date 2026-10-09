package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class QueueShuffleOrderTest {
    /** The queue indexes in the order they play. */
    private fun ShuffleOrder.played(): List<Int> {
        val out = mutableListOf<Int>()
        var i = firstIndex
        while (i != C.INDEX_UNSET) {
            out += i
            i = getNextIndex(i)
        }
        return out
    }

    private fun shuffled(count: Int, start: Int, seed: Int = 1) = QueueShuffleOrder(Random(seed)).cloneAndSet(count, start)

    @Test
    fun `a new queue starts with the song picked and plays all of it`() {
        for (seed in 0 until 50) {
            val order = shuffled(12, 7, seed).played()
            assertEquals(7, order.first())
            assertEquals((0 until 12).toList(), order.sorted())
        }
        // No start (a plain shuffle): still every song.
        assertEquals((0 until 5).toList(), QueueShuffleOrder(Random(3)).cloneAndSet(5, C.INDEX_UNSET).played().sorted())
        assertEquals(emptyList<Int>(), QueueShuffleOrder().cloneAndSet(0, 0).played())
    }

    @Test
    fun `previous and last follow the same order`() {
        val order = shuffled(6, 2)
        val played = order.played()
        assertEquals(played.last(), order.lastIndex)
        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(played.first()))
        for (i in 1 until played.size) assertEquals(played[i - 1], order.getPreviousIndex(played[i]))
    }

    @Test
    fun `songs added play right after the song before them in the queue`() {
        val order = shuffled(10, 4)
        val before = order.played()
        // "Play next" while queue item 4 plays: two songs at queue index 5.
        val after = order.cloneAndInsert(5, 2).played()
        val shifted = before.map { if (it >= 5) it + 2 else it }
        val at = shifted.indexOf(4) + 1
        assertEquals(shifted.take(at), after.take(at))
        assertEquals(setOf(5, 6), after.subList(at, at + 2).toSet())
        assertEquals(shifted.drop(at), after.drop(at + 2))
    }

    @Test
    fun `play next works when the song playing is last in the queue`() {
        val order = shuffled(5, 4)
        val after = order.cloneAndInsert(5, 1).played()
        assertEquals(listOf(4, 5), after.take(2))
    }

    @Test
    fun `after the last song to play is the end of the play order`() {
        val order = shuffled(8, 3)
        val last = order.lastIndex
        val after = order.cloneAndInsert(last + 1, 3).played()
        assertEquals(setOf(last + 1, last + 2, last + 3), after.takeLast(3).toSet())
    }

    @Test
    fun `songs added at the start play first`() {
        val after = shuffled(4, 2).cloneAndInsert(0, 1).played()
        assertEquals(0, after.first())
        assertEquals(listOf(1, 2, 3, 4), after.drop(1).sorted())
    }

    @Test
    fun `a swapped song keeps its turn`() {
        val order = shuffled(9, 0)
        val before = order.played()
        // What replaceMediaItem does: the new item goes in after the old one, then the old one goes.
        for (index in 0 until 9) {
            assertEquals("index $index", before, order.cloneAndInsert(index + 1, 1).cloneAndRemove(index, index + 1).played())
        }
    }

    @Test
    fun `removing songs keeps the order of the rest`() {
        val order = shuffled(7, 5)
        val before = order.played()
        val after = order.cloneAndRemove(2, 4).played()
        assertEquals(before.filter { it !in 2..3 }.map { if (it >= 4) it - 2 else it }, after)
        assertEquals(emptyList<Int>(), order.cloneAndClear().played())
    }

    @Test
    fun `moved songs keep their turn`() {
        val order = shuffled(6, 1)
        val before = order.played()
        // Queue items 1 and 2 move to the end: 0 3 4 5 1 2.
        val newIndexOf = mapOf(0 to 0, 3 to 1, 4 to 2, 5 to 3, 1 to 4, 2 to 5)
        assertEquals(before.map { newIndexOf.getValue(it) }, order.cloneAndMove(1, 3, 4).played())
    }
}
