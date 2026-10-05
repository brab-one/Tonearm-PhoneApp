package io.github.deadeyebarb.tonearm.connect

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
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
    private lateinit var lidarr: LidarrConfig

    @Before
    fun setUp() {
        server.start()
        client = ConnectClient(IntegrationHttp(OkHttpClient()) { null }, json)
        lidarr = LidarrConfig(url = server.url("/lidarr").toString(), keyEnc = "")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `publishing posts the state in the hidden payload field of a plugin resource`() = runTest {
        respond("""{"ok":true,"seq":0}""")
        val state = DeviceState("desktop-1", "Desk", "desktop", "https://music.example", PlaybackState(playing = true, index = 0, queue = listOf(ConnectSong("s1", title = "Song"))))
        client.publish(lidarr, "key", state)
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
        val devices = client.devices(lidarr, "key")
        assertEquals(listOf("Desk"), devices.map { it.state.name })
        assertTrue(devices.single().online)

        respond("""{"commands":[{"seq":7,"from":"phone-1","payload":"{\"type\":\"seek\",\"positionMs\":90000}"}],"seq":7}""")
        val (commands, seq) = client.poll(lidarr, "key", "desktop-1", after = 5, waitSeconds = 20)
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
        client.devices(lidarr, "key")
    }

    @Test
    fun `plugin errors come through`() = runTest {
        respond("""{"error":"device is required"}""")
        try {
            client.poll(lidarr, "key", "", 0, 0)
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals("Lidarr: Tonearm Connect: device is required", e.message)
        }
    }

    @Test
    fun `remote positions move on while playing`() {
        val state = PlaybackState(playing = true, positionMs = 10_000, durationMs = 60_000, at = 1_000)
        assertEquals(15_000, state.positionAt(now = 6_000))
        assertEquals(60_000, state.positionAt(now = 100_000))
        assertEquals(10_000, state.copy(playing = false).positionAt(now = 6_000))
    }
}
