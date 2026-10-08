package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

/** Deleting music through a fake Lidarr. */
class MusicRemovalTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private lateinit var removal: MusicRemoval
    private lateinit var lidarr: LidarrConfig

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                synchronized(calls) {
                    calls += "${request.method} $path"
                    bodies += request.body?.utf8().orEmpty()
                }
                val body = when {
                    path == "/api/v1/artist" -> """[{"id":7,"artistName":"The Oscillators"},{"id":8,"artistName":"Signal Garden"},{"id":9,"artistName":"Weezer"}]"""
                    path == "/api/v1/album?artistId=7" -> """[{"id":70,"title":"Hi-Res Tones (Deluxe Edition)","artistId":7},{"id":71,"title":"CD Quality","artistId":7}]"""
                    // Self-titled albums, and an album next to its own deluxe edition.
                    path == "/api/v1/album?artistId=9" -> """[
                        {"id":90,"title":"Weezer","artistId":9,"releaseDate":"1994-05-10T00:00:00Z"},
                        {"id":91,"title":"Weezer","artistId":9,"releaseDate":"2001-05-15T00:00:00Z"},
                        {"id":92,"title":"Pinkerton","artistId":9},
                        {"id":93,"title":"Pinkerton (Deluxe Edition)","artistId":9}]"""
                    path == "/api/v1/track?albumId=90" -> """[{"id":11,"title":"Buddy Holly","trackNumber":"4","mediumNumber":1,"hasFile":true,"trackFileId":901}]"""
                    path == "/api/v1/track?albumId=91" -> """[{"id":12,"title":"Island in the Sun","trackNumber":"4","mediumNumber":1,"hasFile":true,"trackFileId":911}]"""
                    path == "/api/v1/track?albumId=92" -> """[{"id":13,"title":"El Scorcho","trackNumber":"3","mediumNumber":1,"hasFile":true,"trackFileId":921}]"""
                    path == "/api/v1/track?albumId=93" -> """[{"id":14,"title":"El Scorcho","trackNumber":"3","mediumNumber":1,"hasFile":true,"trackFileId":931}]"""
                    path == "/api/v1/track?albumId=70" -> """[
                        {"id":1,"title":"Middle C","trackNumber":"1","mediumNumber":1,"hasFile":true,"trackFileId":101},
                        {"id":2,"title":"Intro","trackNumber":"2","mediumNumber":1,"hasFile":true,"trackFileId":102},
                        {"id":3,"title":"Intro","trackNumber":"1","mediumNumber":2,"hasFile":true,"trackFileId":103},
                        {"id":4,"title":"Octave Up","trackNumber":"3","mediumNumber":1,"hasFile":false,"trackFileId":0}]"""
                    else -> "{}"
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        removal = MusicRemoval(LidarrClient(IntegrationHttp(OkHttpClient()) { null }, json))
        lidarr = LidarrConfig(url = server.url("/").toString())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a song's file goes and its album stops being watched`() = runTest {
        assertTrue(removal.song(lidarr, "k", listOf("The Oscillators feat. Someone"), "Hi-Res Tones", "Middle C (Remastered)", 1, 1))
        assertTrue("DELETE /api/v1/trackfile/101" in calls)
        val monitor = calls.indexOf("PUT /api/v1/album/monitor")
        val body = json.parseToJsonElement(bodies[monitor]).jsonObject
        assertEquals(70, body["albumIds"]!!.jsonArray.single().jsonPrimitive.content.toInt())
        assertFalse(body["monitored"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `songs with the same title are told apart by disc and number`() = runTest {
        assertTrue(removal.song(lidarr, "k", listOf("The Oscillators"), "Hi-Res Tones", "Intro", 1, 2))
        assertTrue("DELETE /api/v1/trackfile/103" in calls)
        assertFalse(calls.any { it.startsWith("DELETE /api/v1/trackfile/102") })
    }

    @Test
    fun `nothing is deleted when unsure`() = runTest {
        // No title match, a track without a file, two same titles without numbers, and an artist or album Lidarr doesn't have.
        assertFalse(removal.song(lidarr, "k", listOf("The Oscillators"), "Hi-Res Tones", "Some Other Song", 1, 1))
        assertFalse(removal.song(lidarr, "k", listOf("The Oscillators"), "Hi-Res Tones", "Octave Up", 3, 1))
        assertFalse(removal.song(lidarr, "k", listOf("The Oscillators"), "Hi-Res Tones", "Intro", null, null))
        assertFalse(removal.song(lidarr, "k", listOf("Nobody"), "Hi-Res Tones", "Middle C", 1, 1))
        assertFalse(removal.song(lidarr, "k", listOf("The Oscillators"), "Lost Album", "Middle C", 1, 1))
        assertFalse(calls.any { it.startsWith("DELETE") || it.startsWith("PUT") })
    }

    @Test
    fun `everything by an artist only goes for that very name`() = runTest {
        // "Signal Garden & Friends" isn't Signal Garden: Lidarr doesn't have that artist, so nothing is deleted.
        assertFalse(removal.artist(lidarr, "k", "Signal Garden & Friends"))
        assertFalse(removal.artist(lidarr, "k", "Signal Garden feat. Someone"))
        assertFalse(calls.any { it.startsWith("DELETE") })
    }

    @Test
    fun `same-titled albums are told apart by year, or left alone`() = runTest {
        assertFalse(removal.album(lidarr, "k", "Weezer", "Weezer"))
        assertTrue(removal.album(lidarr, "k", "Weezer", "Weezer", year = 2001))
        assertEquals(listOf("DELETE /api/v1/album/91?deleteFiles=true&addImportListExclusion=true"), calls.filter { it.startsWith("DELETE") })
    }

    @Test
    fun `an album's own title wins over its deluxe edition`() = runTest {
        assertTrue(removal.song(lidarr, "k", listOf("Weezer"), "Pinkerton (Deluxe Edition)", "El Scorcho", 3, 1))
        assertEquals(listOf("DELETE /api/v1/trackfile/931"), calls.filter { it.startsWith("DELETE") })
        // Without its own title the album can't be told apart, so nothing goes.
        assertFalse(removal.song(lidarr, "k", listOf("Weezer"), "Pinkerton (Remastered)", "El Scorcho", 3, 1))
        assertEquals(1, calls.count { it.startsWith("DELETE") })
    }

    @Test
    fun `a song is found across same-titled albums when only one has it`() = runTest {
        assertTrue(removal.song(lidarr, "k", listOf("Weezer"), "Weezer", "Island in the Sun", 4, 1))
        assertEquals(listOf("DELETE /api/v1/trackfile/911"), calls.filter { it.startsWith("DELETE") })
        // The album stops being watched before its file goes.
        assertTrue(calls.indexOf("PUT /api/v1/album/monitor") < calls.indexOf("DELETE /api/v1/trackfile/911"))
    }

    @Test
    fun `an album or an artist goes with its files and stays excluded`() = runTest {
        assertTrue(removal.album(lidarr, "k", "The Oscillators", "CD Quality"))
        assertTrue("DELETE /api/v1/album/71?deleteFiles=true&addImportListExclusion=true" in calls)
        assertTrue(removal.artist(lidarr, "k", "Signal Garden"))
        assertTrue("DELETE /api/v1/artist/8?deleteFiles=true&addImportListExclusion=true" in calls)
    }
}
