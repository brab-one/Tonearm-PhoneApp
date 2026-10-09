package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "in your library" icon for songs from outside it, against two music servers. */
class FetchTrackerTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val servers = mutableListOf<MockWebServer>()

    /** A music server that has Intro by The xx when [has] says so, and fails while [down]. */
    private class Library(var has: Boolean, var down: Boolean = false)

    private fun serve(library: Library): ServerSession {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (library.down) return MockResponse(code = 503)
                val songs = if (library.has) """[{"id":"s1","title":"Intro","artist":"The xx","duration":127}]""" else "[]"
                return MockResponse(body = """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{"song":$songs}}}""")
            }
        }
        server.start()
        servers += server
        return ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice"), "secret", OkHttpClient())
    }

    @After
    fun tearDown() = servers.forEach { it.close() }

    private val ref = TrackRef("Intro", "The xx", youtubeId = "yt1")

    @Test
    fun `what one server has says nothing about another`() = runTest {
        val home = serve(Library(has = true))
        val away = serve(Library(has = false))
        val tracker = FetchTracker(LidarrClient(IntegrationHttp(OkHttpClient()) { null }, json), SubsonicApi({ home }, json))
        assertTrue(tracker.inLibrary(ref, home))
        assertFalse(tracker.inLibrary(ref, away))
    }

    @Test
    fun `a lookup that failed is tried again`() = runTest {
        val library = Library(has = true, down = true)
        val session = serve(library)
        val tracker = FetchTracker(LidarrClient(IntegrationHttp(OkHttpClient()) { null }, json), SubsonicApi({ session }, json))
        assertFalse(tracker.inLibrary(ref, session))
        library.down = false
        assertTrue(tracker.inLibrary(ref, session))
    }
}
