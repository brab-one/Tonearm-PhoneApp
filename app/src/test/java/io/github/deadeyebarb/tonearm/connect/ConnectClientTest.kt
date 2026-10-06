package io.github.deadeyebarb.tonearm.connect

import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.Integrations
import io.github.deadeyebarb.tonearm.data.MalojaConfig
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
    private lateinit var lidarr: ConnectRoute.Lidarr

    @Before
    fun setUp() {
        server.start()
        client = ConnectClient(IntegrationHttp(OkHttpClient()) { null }, json)
        lidarr = ConnectRoute.Lidarr(LidarrConfig(url = server.url("/lidarr").toString(), keyEnc = ""), "key")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `publishing posts the state in the hidden payload field of a plugin resource`() = runTest {
        respond("""{"ok":true,"seq":0}""")
        val state = DeviceState("desktop-1", "Desk", "desktop", "https://music.example", PlaybackState(playing = true, index = 0, queue = listOf(ConnectSong("s1", title = "Song"))))
        client.publish(lidarr, state)
        val request = server.takeRequest()
        assertEquals("/lidarr/api/v1/notification/action/tonearm", request.url.encodedPath)
        assertEquals("publish", request.url.queryParameter("op"))
        assertEquals("desktop-1", request.url.queryParameter("device"))
        assertEquals("key", request.headers["X-Api-Key"])
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("TonearmConnect", body["implementation"]!!.jsonPrimitive.content)
        assertEquals("TonearmConnectSettings", body["configContract"]!!.jsonPrimitive.content)
        val field = body["fields"]!!.jsonArray.single().jsonObject
        assertEquals("payload", field["name"]!!.jsonPrimitive.content)
        assertEquals(state, json.decodeFromString(DeviceState.serializer(), field["value"]!!.jsonPrimitive.content))
    }

    @Test
    fun `devices and polled commands are read from the plugin's answers`() = runTest {
        // As the real plugin answers (Lidarr's camelCase JSON, states as strings).
        respond("""{"devices":[{"id":"desktop-1","online":true,"secondsSinceSeen":3,"state":"{\"id\":\"desktop-1\",\"name\":\"Desk\",\"kind\":\"desktop\"}"},{"id":"junk","online":false,"secondsSinceSeen":9,"state":"not json"}]}""")
        val devices = client.devices(lidarr)
        assertEquals(listOf("Desk"), devices.map { it.state.name })
        assertTrue(devices.single().online)

        respond("""{"commands":[{"seq":7,"from":"phone-1","payload":"{\"type\":\"seek\",\"positionMs\":90000}"}],"seq":7}""")
        val (commands, seq) = client.poll(lidarr, "desktop-1", after = 5, waitSeconds = 20)
        assertEquals(7L, seq)
        assertEquals(ConnectCommand(ConnectCommand.SEEK, positionMs = 90_000), commands.single().command)
        assertEquals("phone-1", commands.single().from)
        val request = server.takeRequest().let { server.takeRequest() }
        assertEquals("5", request.url.queryParameter("after"))
        assertEquals("20", request.url.queryParameter("wait"))
    }

    @Test(expected = ConnectPluginMissingException::class)
    fun `a lidarr without the plugin is reported as such`() = runTest {
        // What Lidarr 3.1 answers when no provider has that implementation name.
        respond("""{"message":"Value can not be null. (Parameter 'targetType')","description":"System.ArgumentNullException…"}""", 500)
        client.devices(lidarr)
    }

    @Test
    fun `plugin errors come through`() = runTest {
        respond("""{"error":"device is required"}""")
        try {
            client.poll(lidarr, "", 0, 0)
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals("Lidarr: Tonearm Connect: device is required", e.message)
        }
    }

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
    fun `the router prefers the tonearm server and remembers it`() = runTest {
        val router = ConnectRouter(client)
        respond("""{"server":"tonearm","protocol":2,"version":"1.0.0","user":"alice"}""")
        val route = router.route(session()) { error("Lidarr isn't asked when the server is there") }
        assertTrue(route is ConnectRoute.Server)
        assertTrue(router.route(session()) { null } is ConnectRoute.Server)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `without a tonearm server the router falls back to lidarr`() = runTest {
        val config = LidarrConfig(url = "http://lidarr.example", keyEnc = "")
        // Navidrome's answer for a page it doesn't have, and its web app.
        respond("404 page not found", 404)
        assertEquals(ConnectRoute.Lidarr(config, "k"), ConnectRouter(client).route(session()) { config to "k" })
        respond("<!doctype html><html></html>")
        assertEquals(ConnectRoute.Lidarr(config, "k"), ConnectRouter(client).route(session()) { config to "k" })
        respond("404 page not found", 404)
        try {
            ConnectRouter(client).route(session()) { null }
            error("expected failure")
        } catch (_: ConnectUnavailableException) {
        }
    }

    @Test
    fun `a tonearm server that rejects the login isn't skipped`() = runTest {
        respond("""{"error":"Navidrome didn't accept that login"}""", 401)
        try {
            ConnectRouter(client).route(session()) { LidarrConfig(url = "http://lidarr.example", keyEnc = "") to "k" }
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
        http.newCall(okhttp3.Request.Builder().url(server.url("/connect-tonearm/maloja/apis/mlj_1/scrobbles")).build()).execute().close()
        http.newCall(okhttp3.Request.Builder().url(server.url("/rest/ping.view")).build()).execute().close()
        val (lidarr, maloja, other) = List(3) { server.takeRequest().url }
        assertEquals("alice", lidarr.queryParameter("u"))
        assertEquals(lidarr.queryParameter("s"), maloja.queryParameter("s"))
        assertEquals(SubsonicAuth.token("wonderland", lidarr.queryParameter("s")!!), lidarr.queryParameter("t"))
        assertEquals(null, other.queryParameter("u"))
    }

    @Test
    fun `the server's lidarr and maloja replace the app's own, keeping its request preferences`() {
        val stored = Integrations(
            lidarr = LidarrConfig(url = "http://lidarr.lan:8686", keyEnc = "secret", rootFolderPath = "/music", monitor = "latest"),
            maloja = MalojaConfig(url = "http://maloja.lan", scrobble = true),
        )
        val info = TonearmServerInfo(baseUrl = "https://music.example/", lidarr = true, lidarrAdmin = false, maloja = true)
        val used = stored.through(info)
        assertEquals("https://music.example/connect-tonearm/lidarr", used.lidarr!!.url)
        assertEquals("", used.lidarr!!.keyEnc)
        assertEquals("/music", used.lidarr!!.rootFolderPath)
        assertEquals("latest", used.lidarr!!.monitor)
        assertTrue(used.lidarr!!.viaServer && used.lidarr!!.limited)
        assertEquals("https://music.example/connect-tonearm/maloja", used.maloja!!.url)
        assertTrue(used.maloja!!.scrobble)
        // Without the server (or without what it doesn't offer) the app's own settings stay.
        assertEquals(stored, stored.through(null))
        assertEquals(stored.maloja, stored.through(info.copy(maloja = false)).maloja)
        assertEquals(null, Integrations().through(info.copy(lidarr = false, maloja = false)).lidarr)
    }

    @Test
    fun `remote positions move on while playing`() {
        val state = PlaybackState(playing = true, positionMs = 10_000, durationMs = 60_000, at = 1_000)
        assertEquals(15_000, state.positionAt(now = 6_000))
        assertEquals(60_000, state.positionAt(now = 100_000))
        assertEquals(10_000, state.copy(playing = false).positionAt(now = 6_000))
    }
}
