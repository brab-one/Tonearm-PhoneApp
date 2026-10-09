package io.github.deadeyebarb.tonearm.download

import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadOrderTest {
    private fun song(album: String, albumId: String, track: Int, disc: Int = 1) =
        QueueSong("s", Song(id = "$albumId-$disc-$track", title = "Track $track", album = album, albumId = albumId, track = track, discNumber = disc))

    @Test
    fun `albums with the same title don't mix`() {
        val songs = listOf(
            song("Greatest Hits", "queen", 1), song("Greatest Hits", "abba", 2), song("Arrival", "arrival", 1),
            song("Greatest Hits", "abba", 1), song("Greatest Hits", "queen", 2), song("greatest hits", "abba", 1, disc = 2),
        )
        val ids = songs.sortedWith(DownloadRepository.ALBUM_ORDER).map { it.song.id }
        assertEquals(listOf("arrival-1-1", "abba-1-1", "abba-1-2", "abba-2-1", "queen-1-1", "queen-1-2"), ids)
    }
}
