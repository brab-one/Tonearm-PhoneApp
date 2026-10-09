package io.github.deadeyebarb.tonearm.mixes

import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.NextSong
import io.github.deadeyebarb.tonearm.integrations.SongMatch

/** When a radio asks for more, and how a batch is laid out: the same on the phone and the desktop. */
object RadioPolicy {
    /** Refill when this many songs or fewer are left in play order. */
    const val AHEAD = 3
    const val BATCH = 25
    /** The most queue keys sent along as "exclude". */
    const val MAX_EXCLUDE = 500

    /** Ask for more now? Never with repeat on, never while one request runs, not before [retryDelayMs] after a failure. */
    fun wantsMore(left: Int, repeatOff: Boolean, busy: Boolean, failures: Int, msSinceFailure: Long): Boolean =
        repeatOff && !busy && left <= AHEAD && (failures <= 0 || msSinceFailure >= retryDelayMs(failures))

    /** How long to wait after [failures] failed asks in a row: 10 s, 20 s, 40 s, then 60 s. */
    fun retryDelayMs(failures: Int): Long = when {
        failures <= 0 -> 0
        failures >= 4 -> 60_000
        else -> 10_000L shl (failures - 1)
    }

    /** The newest [MAX_EXCLUDE] of the queue's keys, oldest first. */
    fun exclude(queueKeys: List<String>): List<String> = queueKeys.takeLast(MAX_EXCLUDE)

    /**
     * No artist twice within [gap] places (two songs by one artist are at least [gap] apart) when there's a choice;
     * order kept otherwise.
     */
    fun spread(songs: List<NextSong>, gap: Int = 3): List<NextSong> {
        if (gap <= 1 || songs.size < 2) return songs
        val left = songs.toMutableList()
        val out = ArrayList<NextSong>(songs.size)
        while (left.isNotEmpty()) {
            val recent = out.takeLast(gap - 1).mapNotNull(::artistOf).toSet()
            val next = left.indexOfFirst { artistOf(it) !in recent }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(next)
        }
        return out
    }

    /** Who a song is by, for [spread]: its first credited artist; null when it doesn't say. */
    private fun artistOf(song: NextSong): String? =
        Names.normalize(SongMatch.primaryArtist(SongMatch.cleanArtist(song.song.artistLabel))).ifEmpty { null }
}
