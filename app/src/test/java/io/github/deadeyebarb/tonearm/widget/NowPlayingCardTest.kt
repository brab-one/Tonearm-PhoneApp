package io.github.deadeyebarb.tonearm.widget

import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.playback.SavedItem
import io.github.deadeyebarb.tonearm.playback.SavedQueue
import io.github.deadeyebarb.tonearm.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingCardTest {
    private val items = listOf(
        SavedItem("srv-1", Song("al1-1", title = "Low Hum", artist = "The Oscillators", coverArt = "al1")),
        SavedItem("ytmusic", Song("dQw4w9WgXcQ", title = "Rewrite", artist = "Sia", coverArt = "https://i.ytimg.com/x.jpg")),
    )

    @Test
    fun `the saved queue's song shows paused, not live`() {
        val card = NowPlayingCard.fromSaved(SavedQueue(items, index = 1, positionMs = 30_000))!!
        assertEquals(NowPlayingCard("ytmusic", "https://i.ytimg.com/x.jpg", "Rewrite", "Sia", playing = false, index = 1, count = 2, live = false), card)
    }

    @Test
    fun `no saved song, or an index past the queue, gives no card`() {
        assertNull(NowPlayingCard.fromSaved(SavedQueue()))
        assertNull(NowPlayingCard.fromSaved(SavedQueue(items, index = 2)))
        assertNull(NowPlayingCard.fromSaved(SavedQueue(items, index = -1)))
    }

    @Test
    fun `the playing song is live`() {
        val card = NowPlayingCard.from(QueueSong("srv-1", items[0].song.copy(displayArtist = "The Oscillators & Co")), playWhenReady = true, index = 0, count = 2)!!
        assertEquals(NowPlayingCard("srv-1", "al1", "Low Hum", "The Oscillators & Co", playing = true, index = 0, count = 2, live = true), card)
        assertNull(NowPlayingCard.from(null, playWhenReady = true, index = 0, count = 0))
    }
}
