package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntegrationClientsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private lateinit var http: IntegrationHttp

    @Before
    fun setUp() {
        server.start()
        http = IntegrationHttp(OkHttpClient()) { null }
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun base(prefix: String = "") = server.url("/$prefix").toString().trimEnd('/')

    @Test
    fun `connection failures name the service`() = runTest {
        val url = base()
        server.close()
        try {
            LidarrClient(http, json).status(LidarrConfig(url), "key")
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals(0, e.code)
            assertTrue(e.message!!, e.message!!.startsWith("Lidarr: "))
        }
    }

    @Test
    fun `the music server's client is used when asked for`() = runTest {
        var asked = 0
        val serverClient = OkHttpClient()
        val withTls = IntegrationHttp(OkHttpClient()) { asked++; serverClient }
        assertTrue(withTls.client(useServerTls = true) === serverClient)
        assertFalse(withTls.client(useServerTls = false) === serverClient)
        assertEquals(1, asked)
    }

    private val lidarr = LidarrConfig(url = "", keyEnc = "")

    @Test
    fun `lidarr search marks artists already in lidarr and keeps raw json`() = runTest {
        respond("""[{"artistName":"Known","foreignArtistId":"mb-1","id":3}]""") // GET /artist
        respond(
            """[{"foreignId":"mb-1","artist":{"artistName":"Known","foreignArtistId":"mb-1","remotePoster":"https://img/known.jpg"}},
               {"foreignId":"mb-2","artist":{"artistName":"New","foreignArtistId":"mb-2","disambiguation":"UK band","images":[{"coverType":"poster","remoteUrl":"https://img/new.jpg"}]}},
               {"foreignId":"al-1","album":{"title":"Debut","foreignAlbumId":"al-1","albumType":"Album","releaseDate":"2019-05-01T00:00:00Z","artist":{"artistName":"New"}}}]""",
        )
        val results = LidarrClient(http, json).search(lidarr.copy(url = base("lidarr")), "key", "new")
        assertEquals(3, results.size)
        assertTrue(results[0].inLidarr)
        assertFalse(results[1].inLidarr)
        assertEquals("https://img/new.jpg", results[1].imageUrl)
        assertEquals("Artist · UK band", results[1].subtitle)
        assertTrue(results[2].isAlbum)
        assertEquals("Album · 2019 · New", results[2].subtitle)
        server.takeRequest()
        val search = server.takeRequest()
        assertEquals("/lidarr/api/v1/search", search.url.encodedPath)
        assertEquals("key", search.headers["X-Api-Key"])
    }

    @Test
    fun `adding an artist merges profiles root folder and add options into lidarr's own json`() = runTest {
        respond("""[]""")
        respond("""[{"artistName":"New","foreignArtistId":"mb-2","overview":"x"}]""")
        val client = LidarrClient(http, json)
        val candidate = client.lookupArtist(lidarr.copy(url = base()), "key", "New").single()
        respond("""{"id":10,"artistName":"New","foreignArtistId":"mb-2"}""")
        client.addArtist(lidarr.copy(url = base()), "key", candidate, LidarrAddDefaults("/music", 2, 1, "all", search = true))
        server.takeRequest(); server.takeRequest()
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/v1/artist", post.url.encodedPath)
        val body = json.parseToJsonElement(post.body!!.utf8()).jsonObject
        assertEquals("mb-2", body["foreignArtistId"]!!.jsonPrimitive.content)
        assertEquals("x", body["overview"]!!.jsonPrimitive.content)
        assertEquals(2, body["qualityProfileId"]!!.jsonPrimitive.int)
        assertEquals(1, body["metadataProfileId"]!!.jsonPrimitive.int)
        assertEquals("/music", body["rootFolderPath"]!!.jsonPrimitive.content)
        val options = body["addOptions"] as JsonObject
        assertEquals("all", options["monitor"]!!.jsonPrimitive.content)
        assertTrue(options["searchForMissingAlbums"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `adding an album monitors only that album`() = runTest {
        respond("""[]""")
        respond("""[{"foreignId":"al-1","album":{"title":"Debut","foreignAlbumId":"al-1","artist":{"artistName":"New","foreignArtistId":"mb-2"}}}]""")
        val client = LidarrClient(http, json)
        val album = client.search(lidarr.copy(url = base()), "key", "Debut").single()
        respond("""{"id":5,"title":"Debut"}""")
        client.addAlbum(lidarr.copy(url = base()), "key", album, LidarrAddDefaults("/music", 2, 1, "all", search = true))
        server.takeRequest(); server.takeRequest()
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertTrue(body["monitored"]!!.jsonPrimitive.boolean)
        assertTrue((body["addOptions"] as JsonObject)["searchForNewAlbum"]!!.jsonPrimitive.boolean)
        val artist = body["artist"] as JsonObject
        assertEquals("/music", artist["rootFolderPath"]!!.jsonPrimitive.content)
        assertEquals("none", (artist["addOptions"] as JsonObject)["monitor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `defaults fall back to the root folder's profiles`() = runTest {
        respond("""[{"id":1,"path":"/music","defaultQualityProfileId":4,"defaultMetadataProfileId":3}]""")
        val defaults = LidarrClient(http, json).resolveDefaults(lidarr.copy(url = base()), "key")
        assertEquals(LidarrAddDefaults("/music", 4, 3, "all", true), defaults)
    }

    @Test
    fun `lidarr validation errors are readable`() = runTest {
        respond("""[{"propertyName":"ForeignArtistId","errorMessage":"This artist has already been added."}]""", code = 400)
        try {
            LidarrClient(http, json).status(lidarr.copy(url = base()), "key")
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals("Lidarr: This artist has already been added.", e.message)
        }
    }

    @Test
    fun `queue progress`() = runTest {
        respond("""{"page":1,"totalRecords":1,"records":[{"id":1,"title":"New - Debut","status":"downloading","size":100.0,"sizeleft":25.0,"timeleft":"00:01:00","artist":{"artistName":"New"},"album":{"title":"Debut"}}]}""")
        val item = LidarrClient(http, json).queue(lidarr.copy(url = base()), "key").single()
        assertEquals(0.75f, item.progress, 0.001f)
        assertEquals("Debut", item.album!!.title)
    }

    @Test
    fun `names match across the history and the library`() {
        assertEquals(Recommender.normalize("Sigur Rós"), Recommender.normalize("sigur ros"))
        assertEquals(Recommender.normalize("The Oscillators"), Recommender.normalize("Oscillators"))
        assertEquals(Recommender.normalize("Simon & Garfunkel"), Recommender.normalize("Simon  Garfunkel"))
    }
}
