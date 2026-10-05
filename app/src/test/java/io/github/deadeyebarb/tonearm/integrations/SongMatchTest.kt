package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMatchTest {
    @Test
    fun `titles lose credits and video decorations`() {
        assertEquals("Karma Police", SongMatch.cleanTitle("Karma Police (Official Music Video)"))
        assertEquals("Get Lucky", SongMatch.cleanTitle("Get Lucky (feat. Pharrell Williams)"))
        assertEquals("Heroes", SongMatch.cleanTitle("Heroes - 2017 Remaster"))
        assertEquals("Heroes", SongMatch.cleanTitle("Heroes (2017 Remastered Version)"))
        assertEquals("Song", SongMatch.cleanTitle("Song ft. Somebody Else"))
    }

    @Test
    fun `YouTube uploads are split into artist and title`() {
        val video = SongMatch.fromYouTube("Radiohead - Karma Police (Official Music Video)", "RadioheadVEVO", 264, "abc", null)
        assertEquals("Radiohead", video.artist)
        assertEquals("Karma Police", video.title)
        // Topic channels have the real title; a dash in it stays.
        val topic = SongMatch.fromYouTube("Hand In Glove - Remastered", "The Smiths - Topic", 200, "def", null)
        assertEquals("The Smiths", topic.artist)
        assertEquals("Hand In Glove", topic.title)
    }

    @Test
    fun `best match prefers the right artist album and length`() {
        val ref = TrackRef("Karma Police", "Radiohead", album = "OK Computer", duration = 262)
        val live = Song("1", title = "Karma Police", artist = "Radiohead", album = "Live", duration = 300)
        val album = Song("2", title = "Karma Police", artist = "Radiohead", album = "OK Computer", duration = 264)
        val cover = Song("3", title = "Karma Police", artist = "Someone Else", album = "Covers", duration = 262)
        assertEquals("2", SongMatch.best(listOf(live, cover, album), ref)?.id)
        assertNull(SongMatch.best(listOf(cover), ref))
    }

    @Test
    fun `featuring credits still match the main artist`() {
        val ref = TrackRef("Get Lucky", "Daft Punk, Pharrell Williams, Nile Rodgers")
        val song = Song("1", title = "Get Lucky (feat. Pharrell Williams)", artist = "Daft Punk")
        assertEquals("1", SongMatch.best(listOf(song), ref)?.id)
    }
}
