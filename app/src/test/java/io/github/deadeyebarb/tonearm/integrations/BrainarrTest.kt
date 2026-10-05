package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.net.forImages
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrainarrTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private lateinit var lidarr: LidarrClient
    private lateinit var config: LidarrConfig

    @Before
    fun setUp() {
        server.start()
        lidarr = LidarrClient(IntegrationHttp(OkHttpClient()) { null }, json)
        config = LidarrConfig(url = server.url("/").toString().trimEnd('/'), keyEnc = "")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    // Trimmed from a real Lidarr 3.1.6 with Brainarr 1.6.1.
    private val listsJson = """[
        {"id":3,"name":"Last.fm","implementation":"LastFmUser","enableAutomaticAdd":true,"tags":[],"fields":[]},
        {"id":1,"name":"Brainarr","implementation":"Brainarr","implementationName":"Brainarr AI Music Discovery",
         "enableAutomaticAdd":true,"shouldMonitor":"entireArtist","tags":[],"listType":"program",
         "fields":[{"name":"provider","value":"ollama"},{"name":"configurationUrl","value":"http://127.0.0.1:11434"},
                   {"name":"modelSelection","value":"qwen2.5:latest"},{"name":"maxRecommendations","value":20},
                   {"name":"discoveryMode","value":"adjacent"},{"name":"recommendationMode","value":"specificAlbums"},
                   {"name":"apiKey"}]}
    ]"""

    @Test
    fun `brainarr lists are found among import lists and summarized`() = runTest {
        respond(listsJson)
        val lists = lidarr.importLists(config, "key")
            .filter { (list, _) -> list.implementation == BrainarrList.IMPLEMENTATION }
            .map { (list, raw) -> BrainarrList.from(list, raw) }
        val list = lists.single()
        assertEquals(1, list.id)
        assertEquals("Ollama · qwen2.5:latest · Adjacent discovery · 20 albums per run", list.summary)
        assertTrue(list.automaticAdd)
        // The raw JSON is kept whole, for sending the list back with a tag added.
        assertEquals("http://127.0.0.1:11434", list.raw["fields"]!!.jsonArray[1].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provider and mode names read well`() {
        assertEquals("LM Studio", BrainarrList.humanize("lmStudio"))
        assertEquals("OpenRouter", BrainarrList.humanize("openRouter"))
        assertEquals("Adjacent", BrainarrList.humanize("adjacent"))
        assertEquals("Some New Thing", BrainarrList.humanize("someNewThing"))
        assertNull(BrainarrList.humanize("3"))
    }

    private fun artist(id: Int, mbid: String, added: String, tags: List<Int> = emptyList(), monitored: Boolean = true, onDisk: Int = 0, wanted: Int = 10) =
        LidarrArtist(
            id = id, artistName = "Artist $id", foreignArtistId = mbid, added = added, tags = tags, monitored = monitored,
            statistics = LidarrStatistics(trackFileCount = onDisk, trackCount = wanted, totalTrackCount = 10),
        )

    @Test
    fun `picks are tagged or recorded artists, newest first`() {
        val artists = listOf(
            artist(1, "a", "2026-09-01T10:00:00Z", tags = listOf(5)),
            artist(2, "b", "2026-10-01T10:00:00Z"),
            artist(3, "c", "2026-10-03T10:00:00Z", tags = listOf(9)),
            artist(4, "d", "2026-08-01T10:00:00Z"),
        )
        val picks = BrainarrPicks.pickArtists(artists, tags = setOf(5), recorded = setOf("b", "zzz"))
        assertEquals(listOf(2, 1), picks.map { it.id })
    }

    @Test
    fun `pick status follows the library, the queue and Lidarr's counts`() {
        val a = artist(1, "a", "")
        assertEquals(PickStatus.IN_LIBRARY, BrainarrPicks.status(a, inLibrary = true, downloading = true))
        assertEquals(PickStatus.DOWNLOADING, BrainarrPicks.status(a, inLibrary = false, downloading = true))
        assertEquals(PickStatus.ON_DISK, BrainarrPicks.status(a.copy(statistics = LidarrStatistics(trackFileCount = 3, trackCount = 10)), false, false))
        assertEquals(PickStatus.WANTED, BrainarrPicks.status(a, false, false))
        assertEquals(PickStatus.NOT_MONITORED, BrainarrPicks.status(a.copy(monitored = false), false, false))
        // Monitored artist with no monitored albums: nothing will be downloaded.
        assertEquals(PickStatus.NOT_MONITORED, BrainarrPicks.status(a.copy(statistics = LidarrStatistics(trackCount = 0)), false, false))
    }

    @Test
    fun `posters come from Lidarr's API route under the configured address`() {
        val withPoster = LidarrArtist(id = 5, images = listOf(LidarrImage("poster", "/MediaCover/5/poster.jpg", "/config/MediaCover/5/poster.jpg")))
        assertEquals(
            "https://music.example/lidarr/api/v1/mediacover/artist/5/poster-250.jpg",
            LidarrClient.posterUrl(LidarrConfig("https://music.example/lidarr/"), withPoster),
        )
        // Lidarr's own URL carries the file's write time: a new poster is a new cache entry.
        val stamped = withPoster.copy(images = listOf(LidarrImage("poster", "/MediaCover/5/poster.jpg?lastWrite=639267460529748457", null)))
        assertEquals(
            "https://music.example/api/v1/mediacover/artist/5/poster-250.jpg?lastWrite=639267460529748457",
            LidarrClient.posterUrl(LidarrConfig("https://music.example"), stamped),
        )
        assertNull(LidarrClient.posterUrl(LidarrConfig("https://music.example"), withPoster.copy(images = emptyList())))
        assertNull(LidarrClient.posterUrl(LidarrConfig("https://music.example"), withPoster.copy(id = 0)))
    }

    @Test
    fun `the api key only goes to lidarr's cover route`() {
        val base = "https://music.example:8443/lidarr"
        assertTrue(IntegrationImageCalls.isLidarrCover(base, "https://music.example:8443/lidarr/api/v1/mediacover/artist/5/poster-250.jpg".toHttpUrl()))
        assertFalse(IntegrationImageCalls.isLidarrCover(base, "https://music.example:8443/lidarr/api/v1/artist".toHttpUrl()))
        assertFalse(IntegrationImageCalls.isLidarrCover(base, "https://music.example:8443/lidarrX/api/v1/mediacover/a.jpg".toHttpUrl()))
        assertFalse(IntegrationImageCalls.isLidarrCover(base, "https://other.example:8443/lidarr/api/v1/mediacover/a.jpg".toHttpUrl()))
        assertFalse(IntegrationImageCalls.isLidarrCover(base, "https://music.example/lidarr/api/v1/mediacover/a.jpg".toHttpUrl()))
        assertFalse(IntegrationImageCalls.isLidarrCover(base, "http://music.example:8443/lidarr/api/v1/mediacover/a.jpg".toHttpUrl()))
        assertFalse(IntegrationImageCalls.isLidarrCover("not a url", "https://music.example/api/v1/mediacover/a.jpg".toHttpUrl()))
    }

    @Test
    fun `image errors fail instead of being cached`() {
        // Lidarr's 404 for a cover that isn't there yet says "cache for a year".
        server.enqueue(MockResponse.Builder().code(404).setHeader("Cache-Control", "max-age=31536000, public").body("{\"status\":404}").build())
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "image/jpeg").body("jpeg").build())
        val base = OkHttpClient()
        val client = base.forImages()
        assertTrue(client === base.forImages())
        val url = server.url("/api/v1/mediacover/artist/3/poster-250.jpg")
        try {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
            error("expected failure")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("404"))
        }
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun `labelling saves the list with the tag and without a connection test`() = runTest {
        respond(listsJson)
        val (list, raw) = lidarr.importLists(config, "key").first { it.first.implementation == "Brainarr" }
        server.takeRequest()
        respond("{}", 202)
        lidarr.updateImportList(config, "key", list.id, JsonObject(raw + ("tags" to JsonArray(listOf(JsonPrimitive(4))))))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/importlist/1", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("forceSave"))
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(4, body["tags"]!!.jsonArray.single().jsonPrimitive.int)
        assertEquals("Brainarr", body["implementation"]!!.jsonPrimitive.content)
        assertEquals(7, body["fields"]!!.jsonArray.size)
    }

    @Test
    fun `running a list and waiting for it`() = runTest {
        respond("""{"id":5,"name":"ImportListSync","status":"started","message":"Starting Import List Refresh for List Brainarr"}""", 201)
        val started = lidarr.startCommand(config, "key", "ImportListSync", "definitionId" to 1)
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("ImportListSync", body["name"]!!.jsonPrimitive.content)
        assertEquals(1, body["definitionId"]!!.jsonPrimitive.int)
        assertFalse(started.finished)

        respond("""{"id":5,"status":"completed","message":"Import List Sync Completed. Items found: 3, Artists added: 3, Albums added: 3"}""")
        val done = lidarr.command(config, "key", 5)
        assertEquals("/api/v1/command/5", server.takeRequest().url.encodedPath)
        assertTrue(done.finished)
    }

    @Test
    fun `getting an unmonitored pick monitors it like a request and searches`() = runTest {
        respond("{}", 202)
        respond("""{"id":9,"name":"ArtistSearch","status":"queued"}""", 201)
        lidarr.monitorArtists(config, "key", listOf(3), monitor = "latest", search = true)
        val studio = server.takeRequest()
        assertEquals("/api/v1/albumstudio", studio.url.encodedPath)
        val body = json.parseToJsonElement(studio.body!!.utf8()).jsonObject
        assertEquals(3, body["artist"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals("latest", body["monitoringOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content)
        val search = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("ArtistSearch", search["name"]!!.jsonPrimitive.content)
        assertEquals(3, search["artistId"]!!.jsonPrimitive.int)
    }
}
