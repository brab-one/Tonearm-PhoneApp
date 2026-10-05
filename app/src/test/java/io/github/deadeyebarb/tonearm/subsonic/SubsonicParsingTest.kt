package io.github.deadeyebarb.tonearm.subsonic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubsonicParsingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `parses a Navidrome-style getAlbum with OpenSubsonic fields`() {
        val body = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0","openSubsonic":true,
             "album":{"id":"al1","name":"Kind of Blue","artist":"Miles Davis","artistId":"ar1","coverArt":"al-al1","songCount":2,
              "duration":1100,"year":1959,"genre":"Jazz","genres":[{"name":"Jazz"}],"discTitles":[{"disc":1,"title":""}],
              "isCompilation":false,"moods":[],"explicitStatus":"",
              "song":[
                {"id":"s1","parent":"al1","isDir":false,"title":"So What","album":"Kind of Blue","artist":"Miles Davis","track":1,
                 "year":1959,"coverArt":"mf-s1","size":98765432,"contentType":"audio/flac","suffix":"flac","duration":562,
                 "bitRate":2116,"bitDepth":24,"samplingRate":96000,"channelCount":2,"discNumber":1,"albumId":"al1","artistId":"ar1",
                 "type":"music","isVideo":false,"bpm":0,"replayGain":{"trackGain":-6.5,"albumGain":-7.1,"trackPeak":0.98,"albumPeak":0.99},
                 "artists":[{"id":"ar1","name":"Miles Davis"}],"contributors":[]},
                {"id":"s2","title":"Freddie Freeloader","track":2,"suffix":"flac","duration":538,"starred":"2024-01-01T00:00:00Z"}
              ]}}}
        """.trimIndent()
        val response = SubsonicApi.parse(json, body)
        assertTrue(response.openSubsonic)
        val album = response.album!!
        assertEquals("Kind of Blue", album.name)
        assertEquals(1959, album.year)
        assertEquals(2, album.song.size)
        val song = album.song[0]
        assertEquals(24, song.bitDepth)
        assertEquals(96000, song.samplingRate)
        assertEquals(-6.5f, song.replayGain!!.trackGain!!, 0.001f)
        assertNull(song.starred)
        assertNotNull(album.song[1].starred)
    }

    @Test
    fun `numeric ids from older servers are read as strings`() {
        val body = """{"subsonic-response":{"status":"ok","version":"1.15.0","randomSongs":{"song":[{"id":123,"title":"A","albumId":45,"duration":"200"}]}}}"""
        val song = SubsonicApi.parse(json, body).randomSongs!!.song.single()
        assertEquals("123", song.id)
        assertEquals("45", song.albumId)
    }

    @Test
    fun `api errors become exceptions with the server's code`() {
        val body = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
        try {
            SubsonicApi.parse(json, body)
            fail("expected an exception")
        } catch (e: SubsonicApiException) {
            assertEquals(40, e.code)
            assertEquals("Wrong username or password", e.userMessage())
        }
    }

    @Test(expected = SubsonicProtocolException::class)
    fun `html from a misconfigured proxy is rejected clearly`() {
        SubsonicApi.parse(json, "<html><body>Login</body></html>")
    }

    @Test
    fun `structured lyrics are parsed`() {
        val body = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
              {"lang":"eng","synced":true,"offset":100,"line":[{"start":1100,"value":"one"},{"start":2100,"value":"two"}]}]}}}
        """.trimIndent()
        val lyrics = Lyrics.from(SubsonicApi.parse(json, body).lyricsList!!.structuredLyrics.single())
        assertTrue(lyrics.synced)
        assertEquals(listOf(1000L, 2000L), lyrics.lines.map { it.startMs })
    }
}
