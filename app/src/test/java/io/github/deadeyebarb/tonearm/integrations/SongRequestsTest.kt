package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SongRequestsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private val posted = mutableListOf<Pair<String, String>>()
    private val recordingQueries = mutableListOf<String>()
    private lateinit var requests: SongRequests
    private lateinit var config: LidarrConfig

    private val album = """{"title":"OK Computer","foreignAlbumId":"rg-ok","albumType":"Album","monitored":false,
        "artist":{"artistName":"Radiohead","foreignArtistId":"a-rh"}}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val term = request.url.queryParameter("term").orEmpty()
                if (request.method == "POST") posted += path to request.body!!.utf8()
                if (path == "/ws/2/recording") recordingQueries += request.url.queryParameter("query").orEmpty()
                return when {
                    path == "/ws/2/recording" && "Unknown" in request.url.queryParameter("query").orEmpty() -> MockResponse(body = """{"recordings":[]}""")
                    path == "/ws/2/recording" -> MockResponse(body = """{"recordings":[{"id":"r1","title":"Karma Police","score":100,
                        "artist-credit":[{"name":"Radiohead"}],"releases":[
                        {"status":"Official","date":"2008","release-group":{"id":"rg-best","title":"The Best Of","primary-type":"Album","secondary-types":["Compilation"]}},
                        {"status":"Official","date":"1997-08-25","release-group":{"id":"rg-single","title":"Karma Police","primary-type":"Single"}},
                        {"status":"Official","date":"1997-05-21","release-group":{"id":"rg-ok","title":"OK Computer","primary-type":"Album"}}]}]}""")
                    path == "/api/v1/album/lookup" && (term == "lidarr:rg-ok" || term == "Radiohead OK Computer") -> MockResponse(body = "[$album]")
                    path == "/api/v1/album/lookup" -> MockResponse(body = "[]")
                    path == "/api/v1/artist/lookup" && term == "Radiohead" -> MockResponse(body = """[{"artistName":"Radiohead","foreignArtistId":"a-rh"}]""")
                    path == "/api/v1/artist/lookup" -> MockResponse(body = """[{"artistName":"Nobody Known","foreignArtistId":"a-n"}]""")
                    path == "/api/v1/artist" -> MockResponse(body = "[]")
                    path == "/api/v1/rootfolder" -> MockResponse(body = """[{"id":1,"path":"/music","defaultQualityProfileId":2,"defaultMetadataProfileId":3}]""")
                    path == "/api/v1/album" && request.method == "POST" -> MockResponse(body = album)
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
        val http = IntegrationHttp(OkHttpClient()) { null }
        val base = server.url("/").toString()
        requests = SongRequests(LidarrClient(http, json), MusicBrainz(OkHttpClient(), json, base))
        config = LidarrConfig(url = base.trimEnd('/'), keyEnc = "")
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a liked song requests the studio album it first came out on`() = runTest {
        val result = requests.request(config, "k", TrackRef("Karma Police", "Radiohead - Topic"))
        assertEquals(SongRequestResult.Album("OK Computer", "Radiohead"), result)
        val body = json.parseToJsonElement(posted.single { it.first == "/api/v1/album" }.second).jsonObject
        assertTrue(body["monitored"]!!.jsonPrimitive.boolean)
        // Only the album is monitored, not the artist's other albums.
        assertEquals("none", (body["artist"] as JsonObject)["addOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an album named by the source is used directly`() = runTest {
        val result = requests.request(config, "k", TrackRef("Lucky", "Radiohead", album = "OK Computer"))
        assertEquals(SongRequestResult.Album("OK Computer", "Radiohead"), result)
        assertFalse(server.takeRequestPaths().any { it.startsWith("/ws/2/") })
    }

    @Test
    fun `the artist's MusicBrainz id narrows the search`() = runTest {
        requests.request(config, "k", TrackRef("Karma Police", "Radiohead"))
        assertTrue(recordingQueries.any { "arid:a-rh" in it })
    }

    @Test
    fun `no album means no request, not the artist's whole discography`() = runTest {
        val result = requests.request(config, "k", TrackRef("Unknown Song", "Radiohead"))
        assertTrue(result is SongRequestResult.NotFound)
        assertTrue(posted.none { it.first == "/api/v1/artist" })
    }

    @Test
    fun `no exact artist means nothing is added`() = runTest {
        val result = requests.request(config, "k", TrackRef("Unknown Song", "Nobody"))
        assertTrue(result is SongRequestResult.NotFound)
        assertTrue(posted.isEmpty())
    }

    private fun MockWebServer.takeRequestPaths(): List<String> = List(requestCount) { takeRequest().url.encodedPath }
}
