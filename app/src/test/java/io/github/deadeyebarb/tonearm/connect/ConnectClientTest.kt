package io.github.deadeyebarb.tonearm.connect

import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.Integrations
import io.github.deadeyebarb.tonearm.data.TonearmServerInfo
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicAuth
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectClientTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private lateinit var client: ConnectClient

    @Before
    fun setUp() {
        server.start()
        client = ConnectClient(IntegrationHttp(OkHttpClient()) { null }, json)
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun session(auth: AuthMethod = AuthMethod.TOKEN) =
        ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice", auth = auth), "wonderland", OkHttpClient())

    @Test
    fun `the tonearm server gets the music server's login and the data as the body`() = runTest {
        respond("""{"ok":true,"seq":3}""")
        client.send(ConnectRoute.Server(session()), "phone-1", "desktop-1", ConnectCommand(ConnectCommand.PAUSE))
        val request = server.takeRequest()
        assertEquals("/connect-tonearm/api/send", request.url.encodedPath)
        assertEquals("alice", request.url.queryParameter("u"))
        val salt = request.url.queryParameter("s")!!
        assertEquals(SubsonicAuth.token("wonderland", salt), request.url.queryParameter("t"))
        assertEquals("phone-1", request.url.queryParameter("device"))
        assertEquals("desktop-1", request.url.queryParameter("target"))
        assertEquals(ConnectCommand(ConnectCommand.PAUSE), json.decodeFromString(ConnectCommand.serializer(), request.body!!.utf8()))
    }

    @Test
    fun `publishing posts the state as the body`() = runTest {
        respond("""{"ok":true,"seq":0}""")
        val state = DeviceState("desktop-1", "Desk", "desktop", "https://music.example", PlaybackState(playing = true, index = 0, queue = listOf(ConnectSong("s1", title = "Song"))))
        client.publish(ConnectRoute.Server(session()), state)
        val request = server.takeRequest()
        assertEquals("/connect-tonearm/api/publish", request.url.encodedPath)
        assertEquals("desktop-1", request.url.queryParameter("device"))
        assertEquals(state, json.decodeFromString(DeviceState.serializer(), request.body!!.utf8()))
    }

    @Test
    fun `devices and polled commands are read from the server's answers`() = runTest {
        respond("""{"devices":[{"id":"desktop-1","online":true,"secondsSinceSeen":3,"state":"{\"id\":\"desktop-1\",\"name\":\"Desk\",\"kind\":\"desktop\"}"},{"id":"junk","online":false,"secondsSinceSeen":9,"state":"not json"}]}""")
        val devices = client.devices(ConnectRoute.Server(session()))
        assertEquals(listOf("Desk"), devices.map { it.state.name })
        assertTrue(devices.single().online)

        respond("""{"commands":[{"seq":7,"from":"phone-1","payload":"{\"type\":\"seek\",\"positionMs\":90000}"}],"seq":7}""")
        val (commands, seq) = client.poll(ConnectRoute.Server(session()), "desktop-1", after = 5, waitSeconds = 20)
        assertEquals(7L, seq)
        assertEquals(ConnectCommand(ConnectCommand.SEEK, positionMs = 90_000), commands.single().command)
        assertEquals("phone-1", commands.single().from)
        val request = server.takeRequest().let { server.takeRequest() }
        assertEquals("5", request.url.queryParameter("after"))
        assertEquals("20", request.url.queryParameter("wait"))
    }

    @Test
    fun `tonearm server errors name the server`() = runTest {
        respond("""{"error":"Navidrome didn't accept that login"}""", 401)
        try {
            client.devices(ConnectRoute.Server(session()))
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals(401, e.code)
            assertEquals("Tonearm server: Navidrome didn't accept that login", e.message)
        }
    }

    @Test
    fun `the router finds the tonearm server and remembers it`() = runTest {
        val router = ConnectRouter(client)
        respond("""{"server":"tonearm","protocol":2,"version":"1.0.0","user":"alice"}""")
        assertTrue(router.route(session()) is ConnectRoute.Server)
        assertTrue(router.route(session()) is ConnectRoute.Server)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `without a tonearm server connect isn't available`() = runTest {
        // Navidrome's answer for a page it doesn't have.
        respond("404 page not found", 404)
        try {
            ConnectRouter(client).route(session())
            error("expected failure")
        } catch (_: ConnectUnavailableException) {
        }
        respond("<!doctype html><html></html>")
        assertEquals(null, client.findServer(session()))
    }

    @Test
    fun `a tonearm server that rejects the login says so`() = runTest {
        respond("""{"error":"Navidrome didn't accept that login"}""", 401)
        try {
            ConnectRouter(client).route(session())
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun `requests to the tonearm server are signed with one salt for a while, others aren't touched`() = runTest {
        repeat(3) { respond("ok") }
        val session = session()
        val http = session.client
        http.newCall(okhttp3.Request.Builder().url(server.url("/connect-tonearm/lidarr/api/v1/queue")).build()).execute().close()
        http.newCall(okhttp3.Request.Builder().url(server.url("/connect-tonearm/api/listening")).build()).execute().close()
        http.newCall(okhttp3.Request.Builder().url(server.url("/rest/ping.view")).build()).execute().close()
        val (lidarr, api, other) = List(3) { server.takeRequest().url }
        assertEquals("alice", lidarr.queryParameter("u"))
        assertEquals(lidarr.queryParameter("s"), api.queryParameter("s"))
        assertEquals(SubsonicAuth.token("wonderland", lidarr.queryParameter("s")!!), lidarr.queryParameter("t"))
        assertEquals(null, other.queryParameter("u"))
    }

    @Test
    fun `the server's lidarr replaces the app's own, keeping its request preferences`() {
        val stored = Integrations(lidarr = LidarrConfig(url = "http://lidarr.lan:8686", keyEnc = "secret", rootFolderPath = "/music", monitor = "latest"))
        val info = TonearmServerInfo(baseUrl = "https://music.example/", lidarr = true, lidarrAdmin = false)
        val used = stored.through(info)
        assertEquals("https://music.example/connect-tonearm/lidarr", used.lidarr!!.url)
        assertEquals("", used.lidarr!!.keyEnc)
        assertEquals("/music", used.lidarr!!.rootFolderPath)
        assertEquals("latest", used.lidarr!!.monitor)
        assertTrue(used.lidarr!!.viaServer && used.lidarr!!.limited)
        // Lidarr only comes through the server.
        assertEquals(Integrations(), stored.through(null))
        assertEquals(null, stored.through(info.copy(lidarr = false)).lidarr)
    }

    @Test
    fun `plays go to the server's history and come back as listening`() = runTest {
        respond("""{"server":"tonearm","protocol":2,"history":true,"ai":"Claude claude-opus-5-5"}""")
        val info = client.findServer(session())!!
        assertTrue(info.history)
        assertEquals("Claude claude-opus-5-5", info.ai)
        server.takeRequest()
        respond("""{"ok":true,"added":1}""")
        client.played(session(), listOf(Played(5, "Sia", "Rewrite", durationMs = 200_000, listenedMs = 150_000, source = "youtube")))
        val sent = server.takeRequest()
        assertEquals("/connect-tonearm/api/played", sent.url.encodedPath)
        val play = Json.parseToJsonElement(sent.body!!.utf8()).jsonArray.single().jsonObject
        assertEquals("youtube", play["source"]!!.jsonPrimitive.content)
        assertEquals(150_000L, play["listenedMs"]!!.jsonPrimitive.long)
        respond("""{"since":1,"plays":3,"artists":[{"artist":"Sia","plays":3,"skips":1,"lastPlayed":9}],"recent":[{"artist":"Sia","title":"Rewrite","at":9}]}""")
        val listening = client.listening(session(), days = 30, artists = 10, recent = 5)
        assertEquals(ArtistCount("Sia", 3, 1, 9), listening.artists.single())
        assertEquals("Rewrite", listening.recent.single().title)
        assertEquals("30", server.takeRequest().url.queryParameter("days"))
        respond("""{"ok":true}""")
        client.dismiss(session(), "Mazzy Star")
        val dismissed = server.takeRequest().url
        assertEquals("/connect-tonearm/api/dismiss", dismissed.encodedPath)
        assertEquals("Mazzy Star", dismissed.queryParameter("artist"))
        assertEquals(null, dismissed.queryParameter("album"))
    }

    @Test
    fun `discovery picks and similar artists come from the tonearm server`() = runTest {
        respond("""{"server":"tonearm","protocol":2,"discovery":true,"recommendations":false}""")
        assertTrue(client.findServer(session())!!.discovery)
        respond("""{"picks":[{"artist":"Björk","album":"Homogenic","year":1997,"because":["Radiohead","Portishead"]}],"madeAt":5}""")
        val picks = client.discover(session(), refresh = true)
        assertEquals(DiscoveryPick("Björk", "Homogenic", 1997, because = listOf("Radiohead", "Portishead")), picks.picks.single())
        assertEquals("true", server.takeRequest().let { server.takeRequest() }.url.queryParameter("refresh"))
        respond("""{"artist":"Portishead","similar":[{"artist":"Massive Attack","inLibrary":true},{"artist":"Tricky","imageUrl":"https://img/t.jpg"}]}""")
        val similar = client.similarArtists(session(), "Portishead")
        assertEquals(listOf("Massive Attack" to true, "Tricky" to false), similar.map { it.artist to it.inLibrary })
        assertEquals("Portishead", server.takeRequest().url.queryParameter("artist"))
    }

    @Test
    fun `remote positions move on while playing`() {
        val state = PlaybackState(playing = true, positionMs = 10_000, durationMs = 60_000, at = 1_000)
        assertEquals(15_000, state.positionAt(now = 6_000))
        assertEquals(60_000, state.positionAt(now = 100_000))
        assertEquals(10_000, state.copy(playing = false).positionAt(now = 6_000))
    }
}
