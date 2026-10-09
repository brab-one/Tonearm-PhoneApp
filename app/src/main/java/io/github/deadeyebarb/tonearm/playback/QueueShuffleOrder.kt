package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * The play order while shuffling. A new queue starts with the song that was picked, so all of it plays.
 * Songs added later play right after the song before them in the queue (first, when added at the start):
 * "Play next" really plays next and a song swapped for its library file keeps its turn. To add songs at
 * the end of the play order, insert them after the last song to play (see [PlayerConnection.enqueue]).
 */
class QueueShuffleOrder private constructor(private val shuffled: IntArray, private val random: Random) : ShuffleOrder {
    /** Where each queue index is in [shuffled]. */
    private val positions = IntArray(shuffled.size).also { p -> shuffled.forEachIndexed { position, index -> p[index] = position } }

    constructor(random: Random = Random.Default) : this(IntArray(0), random)

    override fun getLength() = shuffled.size

    override fun getNextIndex(index: Int): Int = positions[index].let { if (it + 1 < shuffled.size) shuffled[it + 1] else C.INDEX_UNSET }

    override fun getPreviousIndex(index: Int): Int = positions[index].let { if (it > 0) shuffled[it - 1] else C.INDEX_UNSET }

    override fun getFirstIndex(): Int = shuffled.firstOrNull() ?: C.INDEX_UNSET

    override fun getLastIndex(): Int = shuffled.lastOrNull() ?: C.INDEX_UNSET

    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder {
        val rest = (0 until insertionCount).filter { it != startIndex }.shuffled(random)
        val first = listOf(startIndex).filter { it in 0 until insertionCount }
        return QueueShuffleOrder((first + rest).toIntArray(), random)
    }

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val added = (insertionIndex until insertionIndex + insertionCount).shuffled(random)
        val at = if (insertionIndex == 0) 0 else positions[insertionIndex - 1] + 1
        val moved = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }
        return QueueShuffleOrder((moved.take(at) + added + moved.drop(at)).toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val kept = shuffled.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - count else it }
        return QueueShuffleOrder(kept.toIntArray(), random)
    }

    /** Songs moved in the queue keep their turn in the play order. */
    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val queue = (0 until shuffled.size).toMutableList()
        val block = queue.subList(indexFrom, indexToExclusive).toList()
        repeat(block.size) { queue.removeAt(indexFrom) }
        queue.addAll(newIndexFrom, block)
        val newIndexOf = IntArray(queue.size).also { n -> queue.forEachIndexed { newIndex, oldIndex -> n[oldIndex] = newIndex } }
        return QueueShuffleOrder(shuffled.map { newIndexOf[it] }.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)
}
