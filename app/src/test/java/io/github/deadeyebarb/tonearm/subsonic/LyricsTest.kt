package io.github.deadeyebarb.tonearm.subsonic

import io.github.deadeyebarb.tonearm.data.ServerConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test
    fun `parses LRC with repeated stamps and skips metadata`() {
        val lyrics = Lyrics.parse(
            """
            [ar:Someone]
            [ti:Song]
            [00:01.50]First line
            [00:10.00][00:30.25]Chorus
            [01:02.123]Last
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf(1500L, 10_000L, 30_250L, 62_123L), lyrics.lines.map { it.startMs })
        assertEquals(listOf("First line", "Chorus", "Chorus", "Last"), lyrics.lines.map { it.text })
    }

    @Test
    fun `finds the line being sung`() {
        val lyrics = Lyrics.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(-1, lyrics.indexAt(500))
        assertEquals(0, lyrics.indexAt(1000))
        assertEquals(1, lyrics.indexAt(8999))
        assertEquals(2, lyrics.indexAt(60_000))
    }

    @Test
    fun `plain text stays unsynced`() {
        val lyrics = Lyrics.parse("\nVerse one\n\nVerse two\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("Verse one", "", "Verse two"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.indexAt(1000))
    }

    @Test
    fun `synced lyrics are asked for again after the server couldn't be reached`() = runTest {
        var down = true
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ok = """{"subsonic-response":{"status":"ok","version":"1.16.1""""
                return when (request.url.encodedPath.removePrefix("/rest/").removeSuffix(".view")) {
                    "getOpenSubsonicExtensions" ->
                        if (down) MockResponse(code = 503) else MockResponse(body = """$ok,"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]}}""")
                    "getLyricsBySongId" -> MockResponse(body = """$ok,"lyricsList":{"structuredLyrics":[{"synced":true,"line":[{"start":1000,"value":"Hello"}]}]}}}""")
                    "getLyrics" -> MockResponse(body = """$ok,"lyrics":{"value":"Hello"}}}""")
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
        try {
            val session = ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice"), "secret", OkHttpClient())
            val api = SubsonicApi({ session }, Json { ignoreUnknownKeys = true; explicitNulls = false })
            val song = Song("s1", title = "Song", artist = "Someone")
            // Only the plain lyrics while the server is away for a moment...
            assertFalse(api.lyrics(session, song)!!.synced)
            // ...and the synced ones once it's back.
            down = false
            assertTrue(api.lyrics(session, song)!!.synced)
        } finally {
            server.close()
        }
    }
}
