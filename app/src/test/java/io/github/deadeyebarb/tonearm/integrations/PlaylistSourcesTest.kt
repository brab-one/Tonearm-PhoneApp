package io.github.deadeyebarb.tonearm.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSourcesTest {
    @Test
    fun `links are recognized`() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", PlaylistSources.spotifyPlaylistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", PlaylistSources.spotifyPlaylistId("https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", PlaylistSources.spotifyPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", PlaylistSources.youtubePlaylistId("https://music.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))
        assertEquals("PLabc", PlaylistSources.youtubePlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc"))
        assertEquals("PLabc", PlaylistSources.youtubePlaylistId("https://music.youtube.com/browse/VLPLabc"))
        assertNull(PlaylistSources.youtubePlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `Spotify embed page`() {
        val data = """{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Road Trip","trackList":[
            {"uri":"spotify:track:1","title":"Mr. Brightside","subtitle":"The Killers","duration":222075},
            {"uri":"spotify:track:2","title":"Get Lucky","subtitle":"Daft Punk,Pharrell Williams","duration":369000}]}}}}}}"""
        val html = """<html><script id="__NEXT_DATA__" type="application/json">$data</script></html>"""
        val playlist = PlaylistSources.parseSpotifyEmbed(html)!!
        assertEquals("Road Trip", playlist.name)
        assertEquals(TrackRef("Mr. Brightside", "The Killers", duration = 222), playlist.tracks[0])
        assertEquals("Daft Punk, Pharrell Williams", playlist.tracks[1].artist)
        assertFalse(playlist.truncated)
    }

    @Test
    fun `Exportify CSV with quotes and commas`() {
        val csv = "﻿\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n" +
            "\"spotify:track:1\",\"Hello, Goodbye\",\"The Beatles\",\"Magical Mystery Tour\",\"208000\"\r\n" +
            "\"spotify:track:2\",\"Say \"\"Hi\"\"\",\"A,B\",\"\",\"1000\"\n"
        val playlist = PlaylistSources.parseCsv(csv, "Mine")
        assertEquals(2, playlist.tracks.size)
        assertEquals(TrackRef("Hello, Goodbye", "The Beatles", "Magical Mystery Tour", 208), playlist.tracks[0])
        assertEquals("Say \"Hi\"", playlist.tracks[1].title)
        assertEquals("A, B", playlist.tracks[1].artist)
        assertNull(playlist.tracks[1].album)
    }

    @Test
    fun `semicolon CSV with simple headers`() {
        val playlist = PlaylistSources.parseCsv("title;artist;album\nHeroes;David Bowie;Heroes\n", "x")
        assertEquals(TrackRef("Heroes", "David Bowie", "Heroes"), playlist.tracks.single())
    }

    @Test
    fun `Spotify data export`() {
        val text = """{"playlists":[{"name":"Faves","items":[{"track":{"trackName":"Heroes","artistName":"David Bowie","albumName":"Heroes","trackUri":"x"}},
            {"episode":null,"track":null}]},{"name":"Empty","items":[]}]}"""
        val playlists = PlaylistSources.parseSpotifyDataExport(text)
        assertEquals(1, playlists.size)
        assertEquals("Faves", playlists[0].name)
        assertTrue(playlists[0].tracks.single().title == "Heroes")
    }
}
