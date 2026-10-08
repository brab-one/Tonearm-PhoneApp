package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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

/** Against a fake Lidarr whose metadata knows Radiohead's albums and tracks. */
class SongRequestsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    /** Method, path and body of every change sent to Lidarr. */
    private val changes = mutableListOf<Triple<String, String, String>>()
    private lateinit var requests: SongRequests
    private lateinit var config: LidarrConfig
    private var radioheadInLidarr = false
    private var trackLooks = 0

    private val album = """{"title":"OK Computer","foreignAlbumId":"rg-ok","albumType":"Album","monitored":false,
        "artist":{"artistName":"Radiohead","foreignArtistId":"a-rh"}}"""

    /** Lidarr fills in a new artist's tracks over a few looks. */
    private fun tracks(): String {
        trackLooks++
        val all = listOf(
            """{"id":1,"title":"Karma Police","albumId":12}""",
            """{"id":2,"title":"Karma Police","albumId":11}""",
            """{"id":3,"title":"Karma Police (Remastered)","albumId":10}""",
            """{"id":4,"title":"Lucky","albumId":10}""",
        )
        val shown = if (radioheadInLidarr) all.size else (trackLooks - 1).coerceIn(0, all.size)
        return all.take(shown).joinToString(",", "[", "]")
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val term = request.url.queryParameter("term").orEmpty()
                if (request.method != "GET") changes += Triple(request.method, path + (request.url.encodedQuery?.let { "?$it" } ?: ""), request.body?.utf8().orEmpty())
                return when {
                    path == "/api/v1/album/lookup" && term == "Radiohead OK Computer" -> MockResponse(body = "[$album]")
                    // Kept in someone's private weekly picks.
                    path == "/api/v1/album/lookup" && term == "Slowdive Souvlaki" -> MockResponse(body = """[{"id":30,"title":"Souvlaki","foreignAlbumId":"rg-s",
                        "monitored":true,"artistId":7,"artist":{"id":7,"artistName":"Slowdive","foreignArtistId":"a-s","path":"/picks/carol/Slowdive"}}]""")
                    path == "/api/v1/album/lookup" -> MockResponse(body = "[]")
                    path == "/api/v1/artist/lookup" && term == "Radiohead" ->
                        MockResponse(body = """[{"artistName":"Radiohead","foreignArtistId":"a-rh"${if (radioheadInLidarr) ""","id":4""" else ""}}]""")
                    path == "/api/v1/artist/lookup" -> MockResponse(body = """[{"artistName":"Nobody Known","foreignArtistId":"a-n"}]""")
                    path == "/api/v1/artist" && request.method == "GET" ->
                        MockResponse(body = if (radioheadInLidarr) """[{"id":4,"artistName":"Radiohead","foreignArtistId":"a-rh"}]""" else "[]")
                    path == "/api/v1/artist" && request.method == "POST" -> MockResponse(body = """{"id":4,"artistName":"Radiohead","foreignArtistId":"a-rh"}""")
                    path == "/api/v1/rootfolder" -> MockResponse(body = """[{"id":1,"path":"/music","defaultQualityProfileId":2,"defaultMetadataProfileId":3},
                        {"id":2,"path":"/picks/carol","name":"Tonearm picks: carol"},{"id":3,"path":"/picks/bob","name":"Tonearm picks: bob"}]""")
                    path == "/api/v1/track" -> MockResponse(body = tracks())
                    path == "/api/v1/album" && request.method == "GET" -> MockResponse(body = """[
                        {"id":10,"title":"OK Computer","artistId":4,"albumType":"Album","releaseDate":"1997-05-21T00:00:00Z","secondaryTypes":[]},
                        {"id":11,"title":"Karma Police","artistId":4,"albumType":"Single","releaseDate":"1997-08-25T00:00:00Z"},
                        {"id":12,"title":"The Best Of","artistId":4,"albumType":"Album","releaseDate":"2008-06-02T00:00:00Z","secondaryTypes":[{"id":1,"name":"Compilation"}]}]""")
                    path == "/api/v1/album" && request.method == "POST" -> MockResponse(body = album)
                    path == "/api/v1/album/monitor" || path == "/api/v1/command" || path.startsWith("/api/v1/artist/") -> MockResponse(body = "{}")
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
        val http = IntegrationHttp(OkHttpClient()) { null }
        val base = server.url("/").toString()
        requests = SongRequests(LidarrClient(http, json), pause = { })
        config = LidarrConfig(url = base.trimEnd('/'), keyEnc = "")
    }

    @After
    fun tearDown() = server.close()

    private fun change(method: String, path: String) = changes.single { it.first == method && it.second.startsWith(path) }

    @Test
    fun `a liked song requests the studio album it first came out on, found in lidarr's own track lists`() = runTest {
        val result = requests.request(config, "k", TrackRef("Karma Police", "Radiohead - Topic"))
        assertEquals(SongRequestResult.Album("OK Computer", "Radiohead"), result)
        // The artist went in without monitoring or searching anything…
        val added = json.parseToJsonElement(change("POST", "/api/v1/artist").third).jsonObject
        assertEquals("none", added["addOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content)
        assertFalse(added["addOptions"]!!.jsonObject["searchForMissingAlbums"]!!.jsonPrimitive.boolean)
        // …and only the album with the song is wanted, once Lidarr had listed all the tracks.
        val monitored = json.parseToJsonElement(change("PUT", "/api/v1/album/monitor").third).jsonObject
        assertEquals(10, monitored["albumIds"]!!.jsonArray.single().jsonPrimitive.int)
        assertTrue(trackLooks >= 5)
        assertTrue(changes.none { it.first == "DELETE" })
    }

    @Test
    fun `an artist already in lidarr is used as it is`() = runTest {
        radioheadInLidarr = true
        val result = requests.request(config, "k", TrackRef("Karma Police", "Radiohead"))
        assertEquals(SongRequestResult.Album("OK Computer", "Radiohead"), result)
        assertTrue(changes.none { it.first == "POST" && it.second == "/api/v1/artist" })
        assertEquals(1, trackLooks)
    }

    @Test
    fun `an album named by the source is used directly`() = runTest {
        val result = requests.request(config, "k", TrackRef("Lucky", "Radiohead", album = "OK Computer"))
        assertEquals(SongRequestResult.Album("OK Computer", "Radiohead"), result)
        val body = json.parseToJsonElement(change("POST", "/api/v1/album").third).jsonObject
        assertTrue(body["monitored"]!!.jsonPrimitive.boolean)
        // Only the album is monitored, not the artist's other albums.
        assertEquals("none", (body["artist"] as JsonObject)["addOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content)
        assertEquals(0, trackLooks)
    }

    @Test
    fun `a song on none of the albums requests nothing and takes the artist out again`() = runTest {
        val result = requests.request(config, "k", TrackRef("Unknown Song", "Radiohead"))
        assertTrue(result is SongRequestResult.NotFound)
        assertTrue(change("DELETE", "/api/v1/artist/4").second.contains("deleteFiles=false"))
        assertTrue(changes.none { it.second.startsWith("/api/v1/album/monitor") })
    }

    @Test
    fun `no exact artist means nothing is added`() = runTest {
        val result = requests.request(config, "k", TrackRef("Unknown Song", "Nobody"))
        assertTrue(result is SongRequestResult.NotFound)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `an album in someone else's private picks isn't "already there"`() = runTest {
        val ref = TrackRef(title = "Alison", artist = "Slowdive", album = "Souvlaki")
        val refused = runCatching { requests.request(config, "k", ref) }.exceptionOrNull()
        assertTrue(refused is IntegrationHttpException && refused.code == 409 && "Slowdive" in refused.message.orEmpty())
        // In carol's own picks it is: it's in her library.
        val carol = SongRequests(LidarrClient(IntegrationHttp(OkHttpClient()) { null }, json), ownPicks = { "/picks/carol" }, pause = { })
        assertEquals(SongRequestResult.AlreadyWanted("Souvlaki"), carol.request(config, "k", ref))
    }
}
