package io.github.deadeyebarb.tonearm.likes

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Dislikes against a fake Tonearm server. */
class DislikesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    /** The Tonearm server is down behind its proxy. */
    @Volatile private var down = false
    @Volatile private var hello = """{"server":"tonearm","protocol":2,"history":true,"dislikes":true}"""
    @Volatile private var disliked = """{"songs":[{"artist":"Kongos","title":"Come with Me Now","at":5}],"artists":["Mazzy Star"]}"""
    private var dislikeAnswer = 200
    /** Holds the answer to a dislike back until it's opened. */
    @Volatile private var dislikeGate: CountDownLatch? = null
    private val dislikeCalls = mutableListOf<String>()
    private lateinit var client: ConnectClient
    private lateinit var dislikes: Dislikes
    private var session: ServerSession? = null

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (down) return MockResponse.Builder().code(502).body("Bad gateway").build()
                val body = when (request.url.encodedPath) {
                    "/connect-tonearm/api/hello" -> hello
                    "/connect-tonearm/api/disliked" -> disliked
                    "/connect-tonearm/api/dislike" -> {
                        synchronized(dislikeCalls) { dislikeCalls += request.url.query.orEmpty() }
                        dislikeGate?.await(5, TimeUnit.SECONDS)
                        if (dislikeAnswer != 200) return MockResponse.Builder().code(dislikeAnswer).body("""{"error":"nope"}""").build()
                        """{"ok":true}"""
                    }
                    else -> "{}"
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        client = ConnectClient(IntegrationHttp(OkHttpClient()) { null }, json)
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
    fun `a refresh while a dislike is on its way doesn't drop it`() = runTest {
        dislikes.refresh()
        dislikeGate = CountDownLatch(1)
        val disliking = launch(Dispatchers.Default) { dislikes.set("Sia", "Rewrite", null, true) }
        while (synchronized(dislikeCalls) { dislikeCalls.isEmpty() }) Thread.sleep(10)
        // The server's list doesn't have it yet.
        dislikes.refresh()
        assertTrue(dislikes.isDisliked("Sia", "Rewrite"))
        dislikeGate?.countDown()
        disliking.join()
        assertTrue(dislikes.isDisliked("Sia", "Rewrite"))
        assertTrue(dislikes.isDisliked("Kongos", "Come with Me Now"))
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
        // Nor does another account, on a server that can't be reached: nothing was kept for it.
        session = ServerSession(ServerConfig(name = "Other", baseUrl = "http://127.0.0.1:9/", username = "bob"), "secret", OkHttpClient())
        dislikes.refresh()
        assertTrue(dislikes.state.value.songs.isEmpty())
    }

    @Test
    fun `when the tonearm server can't be reached the last list stays, also after a restart`() = runTest {
        val file = tmp.root.resolve("dislikes.json")
        dislikes = Dislikes(client, ConnectRouter(client), file) { session }
        assertTrue(dislikes.refresh())
        down = true
        // Not reached: the app asks again as soon as a lookup finds the server.
        assertFalse(dislikes.refresh())
        assertTrue(dislikes.isDisliked("Kongos", "Come with Me Now"))
        // Started again with the server down (or in a dead zone): what was kept last time.
        val again = Dislikes(client, ConnectRouter(client), file) { session }
        assertFalse(again.refresh())
        assertTrue(again.isDisliked("Kongos", "Come with Me Now"))
        assertTrue(again.isArtistDisliked("Mazzy Star"))
        // Another account doesn't get them.
        val alice = session
        session = ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "bob"), "builder", OkHttpClient())
        again.refresh()
        assertFalse(again.isDisliked("Kongos", "Come with Me Now"))
        // The server is back: its list.
        session = alice
        down = false
        disliked = """{"songs":[{"artist":"Sia","title":"Rewrite","at":7}],"artists":[]}"""
        assertTrue(again.refresh())
        assertTrue(again.isDisliked("Sia", "Rewrite"))
        assertFalse(again.isDisliked("Kongos", "Come with Me Now"))
    }

    @Test
    fun `a tonearm server that answers it keeps no dislikes clears them, kept that way`() = runTest {
        val file = tmp.root.resolve("dislikes.json")
        dislikes = Dislikes(client, ConnectRouter(client), file) { session }
        dislikes.refresh()
        hello = """{"server":"tonearm","protocol":2,"history":true}"""
        val older = Dislikes(client, ConnectRouter(client), file) { session }
        older.refresh()
        assertTrue(older.state.value.songs.isEmpty())
        down = true
        val again = Dislikes(client, ConnectRouter(client), file) { session }
        again.refresh()
        assertFalse(again.isDisliked("Kongos", "Come with Me Now"))
    }
}
