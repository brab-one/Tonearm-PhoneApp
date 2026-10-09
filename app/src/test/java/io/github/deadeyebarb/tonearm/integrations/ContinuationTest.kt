package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** When the queue ends with another playlist, against a Navidrome that lists an admin everyone's playlists. */
class ContinuationTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ok = """{"subsonic-response":{"status":"ok","version":"1.16.1""""
                return when (request.url.encodedPath.removePrefix("/rest/").removeSuffix(".view")) {
                    "getPlaylists" -> MockResponse(body = """$ok,"playlists":{"playlist":[
                        {"id":"mine","name":"Mine","owner":"alice","songCount":2},
                        {"id":"bobs","name":"Bob's diary","owner":"bob","songCount":2},
                        {"id":"shared","name":"Family","owner":"bob","public":true,"songCount":2}]}}}""")
                    "getPlaylist" -> request.url.queryParameter("id")!!.let { id ->
                        MockResponse(body = """$ok,"playlist":{"id":"$id","entry":[{"id":"$id-1","title":"One"},{"id":"$id-2","title":"Two"}]}}}""")
                    }
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun session(auth: AuthMethod = AuthMethod.TOKEN) =
        ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "Alice", auth = auth), "secret", OkHttpClient())

    private suspend fun picked(session: ServerSession): Set<String> {
        val continuation = Continuation(SubsonicApi({ session }, json), YouTubeMusic(OkHttpClient()))
        return (1..40).map { continuation.anotherPlaylist(session, emptySet()).first().song.id.substringBefore('-') }.toSet()
    }

    @Test
    fun `another playlist is one of yours or a shared one, never someone else's private one`() = runTest {
        assertEquals(setOf("mine", "shared"), picked(session()))
    }

    @Test
    fun `with an api key, which doesn't say who you are, all of them count`() = runTest {
        assertEquals(setOf("mine", "bobs", "shared"), picked(session(AuthMethod.API_KEY)))
    }
}
