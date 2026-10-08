package io.github.deadeyebarb.tonearm.likes

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Dislikes against a fake Tonearm server. */
class DislikesTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private var dislikeAnswer = 200
    private val dislikeCalls = mutableListOf<String>()
    private lateinit var dislikes: Dislikes
    private var session: ServerSession? = null

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.encodedPath) {
                    "/connect-tonearm/api/hello" -> """{"server":"tonearm","protocol":2,"history":true,"dislikes":true}"""
                    "/connect-tonearm/api/disliked" -> """{"songs":[{"artist":"Kongos","title":"Come with Me Now","at":5}],"artists":["Mazzy Star"]}"""
                    "/connect-tonearm/api/dislike" -> {
                        synchronized(dislikeCalls) { dislikeCalls += request.url.query.orEmpty() }
                        if (dislikeAnswer != 200) return MockResponse.Builder().code(dislikeAnswer).body("""{"error":"nope"}""").build()
                        """{"ok":true}"""
                    }
                    else -> "{}"
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        val client = ConnectClient(IntegrationHttp(OkHttpClient()) { null }, json)
        session = ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice"), "wonderland", OkHttpClient())
        dislikes = Dislikes(client, ConnectRouter(client)) { session }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the youtube copy of a disliked song counts as disliked too`() = runTest {
        dislikes.refresh()
        assertTrue(dislikes.isDisliked("Kongos", "Come with Me Now"))
        assertTrue(dislikes.isDisliked("KONGOS - Topic", "Come With Me Now (Official Video)"))
        assertTrue(dislikes.isDisliked("Kongos feat. Someone", "Come with Me Now"))
        assertFalse(dislikes.isDisliked("Kongos", "I'm Only Joking"))
        assertTrue(dislikes.isArtistDisliked("Mazzy Star"))
    }

    @Test
    fun `search puts disliked songs last and songs by artists said no to just before them`() = runTest {
        dislikes.refresh()
        val songs = listOf("Kongos" to "Come with Me Now", "Mazzy Star" to "Fade Into You", "Sia" to "Rewrite", "Kongos" to "Escape")
        assertEquals(
            listOf("Sia" to "Rewrite", "Kongos" to "Escape", "Mazzy Star" to "Fade Into You", "Kongos" to "Come with Me Now"),
            dislikes.demote(songs, { it.first }, { it.second }),
        )
    }

    @Test
    fun `a dislike shows at once and is undone when the server refuses it`() = runTest {
        dislikes.set("Sia", "Rewrite", "Rewrite", true)
        assertTrue(dislikes.isDisliked("Sia", "Rewrite"))
        assertTrue(dislikeCalls.single().contains("on=true"))
        dislikes.set("Sia", "Rewrite", null, false)
        assertFalse(dislikes.isDisliked("Sia", "Rewrite"))
        dislikeAnswer = 500
        try {
            dislikes.set("Sia", "Sunday", null, true)
            fail("expected the failure to come through")
        } catch (_: Exception) {
        }
        assertFalse(dislikes.isDisliked("Sia", "Sunday"))
    }

    @Test
    fun `another server or account starts without the old dislikes`() = runTest {
        dislikes.refresh()
        assertTrue(dislikes.isDisliked("Kongos", "Come with Me Now"))
        // Signed out: nothing is disliked anymore.
        session = null
        dislikes.refresh()
        assertFalse(dislikes.isDisliked("Kongos", "Come with Me Now"))
        assertFalse(dislikes.isArtistDisliked("Mazzy Star"))
        // A server without a Tonearm server keeps none either.
        session = ServerSession(ServerConfig(name = "Other", baseUrl = "http://127.0.0.1:9/", username = "bob"), "secret", OkHttpClient())
        dislikes.refresh()
        assertTrue(dislikes.state.value.songs.isEmpty())
    }
}
