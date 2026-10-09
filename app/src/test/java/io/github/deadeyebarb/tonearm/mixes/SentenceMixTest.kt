package io.github.deadeyebarb.tonearm.mixes

import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** The keyword mix against a fake Navidrome. */
class SentenceMixTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private val requests = mutableListOf<HttpUrl>()
    /** Navidrome without Last.fm knows no top songs. */
    private var topSongs = true

    private fun song(id: String, title: String, artist: String, genre: String) =
        buildJsonObject { put("id", id); put("title", title); put("artist", artist); put("genre", genre) }

    private val library = listOf(
        song("j1", "Slow Rain", "Midnight Quartet", "Jazz"),
        song("j2", "Late Set", "Midnight Quartet", "Jazz"),
        song("j3", "Brushes", "Midnight Quartet", "Jazz"),
        song("j4", "Rainy Sunday Blues", "Blue Notes", "Jazz"),
        song("a1", "Deep Drone", "Signal Garden", "Ambient"),
        song("a2", "Shimmer", "Signal Garden", "Ambient"),
        song("a3", "Garden Rain", "Signal Garden", "Ambient"),
        song("d1", "Bassline", "Breakbeat Unit", "Drum and Bass"),
    )

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun subsonic(body: String) = MockResponse(body = """{"subsonic-response":{"status":"ok","version":"1.16.1",$body}}""")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                synchronized(requests) { requests += url }
                val q = { name: String -> url.queryParameter(name).orEmpty() }
                return when (url.encodedPath.removePrefix("/rest/").removeSuffix(".view")) {
                    "getGenres" -> subsonic(""""genres":{"genre":[{"value":"Jazz"},{"value":"Ambient"},{"value":"Drum and Bass"},{"value":"Bass"},{"value":"Électronique"}]}""")
                    // Navidrome's getSongsByGenre gives a big genre's songs in the same order every time: the mix draws at random.
                    "getRandomSongs" -> subsonic(""""randomSongs":{"song":${JsonArray(library.filter { q("genre").isEmpty() || it.text("genre") == q("genre") })}}""")
                    "getArtists" -> subsonic(""""artists":{"index":[{"name":"M","artist":[{"id":"ar3","name":"Midnight Quartet"}]},{"name":"S","artist":[{"id":"ar2","name":"Signal Garden"}]}]}""")
                    "getTopSongs" -> subsonic(""""topSongs":{"song":${JsonArray(library.filter { topSongs && it.text("artist") == q("artist") }.take(2))}}""")
                    "search3" -> subsonic(""""searchResult3":{"song":${JsonArray(library.filter { q("query").lowercase() in (it.text("title") + " " + it.text("artist")).lowercase() })}}""")
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private val session by lazy { ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice"), "secret", OkHttpClient()) }
    private val api by lazy { SubsonicApi({ session }, json) }
    private fun asked(method: String) = synchronized(requests) { requests.filter { it.encodedPath.endsWith("/$method.view") } }

    @Test
    fun `terms are the words that say something about the music`() {
        assertEquals(listOf("rainy", "sunday", "mellow", "jazz", "evening"), SentenceMix.terms("Rainy Sunday, some mellow JAZZ music for the evening!"))
        assertEquals(emptyList<String>(), SentenceMix.terms("lo-fi, a bit of it"))
        assertEquals(listOf("café", "électro"), SentenceMix.terms("Café électro, a mix like that"))
    }

    @Test
    fun `genres are found in the sentence, longer ones first`() {
        val genres = listOf("Rock", "Jazz", "Acid Jazz", "Bass", "Drum and Bass", "Hip-Hop", "Électronique")
        assertEquals(listOf("Jazz"), SentenceMix.genresIn("rainy sunday, mellow jazz", genres))
        assertEquals(listOf("Drum and Bass"), SentenceMix.genresIn("some drum and bass please", genres))
        assertEquals(listOf("Drum and Bass", "Bass"), SentenceMix.genresIn("drum and bass, then just bass", genres))
        assertEquals(listOf("Acid Jazz"), SentenceMix.genresIn("ACID JAZZ for cooking", genres))
        assertEquals(listOf("Hip-Hop", "Électronique"), SentenceMix.genresIn("hip hop or electronique", genres))
        assertEquals(emptyList<String>(), SentenceMix.genresIn("jazzy rocks", genres))
    }

    @Test
    fun `genres, artists and the other words each bring their songs`() = runTest {
        val songs = SentenceMix.build(api, session, "rainy sunday, mellow jazz like Signal Garden", { _, _ -> false })
        assertEquals(listOf("Jazz"), asked("getRandomSongs").map { it.queryParameter("genre") })
        assertEquals(listOf("Signal Garden"), asked("getTopSongs").map { it.queryParameter("artist") })
        // Words the genre or the artist stand for, and short or empty ones, aren't searched.
        assertEquals(listOf("mellow", "rainy", "sunday"), asked("search3").mapNotNull { it.queryParameter("query") }.sorted())
        // Jazz, Signal Garden's top two, and "rainy" and "sunday" find the song the genre brought already: once.
        assertEquals(listOf("a1", "a2", "j1", "j2", "j3", "j4"), songs.map { it.id }.sorted())
    }

    @Test
    fun `without top songs an artist's songs are found by name`() = runTest {
        topSongs = false
        val songs = SentenceMix.build(api, session, "signal garden", { _, _ -> false })
        assertEquals(listOf("a1", "a2", "a3"), songs.map { it.id }.sorted())
    }

    @Test
    fun `disliked songs are left out, and size is kept`() = runTest {
        val songs = SentenceMix.build(api, session, "mellow jazz", { artist, title -> artist == "Midnight Quartet" && title == "Late Set" })
        assertEquals(listOf("j1", "j3", "j4"), songs.map { it.id }.sorted())
        assertEquals(2, SentenceMix.build(api, session, "mellow jazz", { _, _ -> false }, size = 2).size)
    }

    @Test
    fun `the same sentence gives the same mix, spread over the artists`() = runTest {
        val sentence = "jazz and ambient"
        val first = SentenceMix.build(api, session, sentence, { _, _ -> false })
        assertEquals(first, SentenceMix.build(api, session, sentence, { _, _ -> false }))
        assertEquals(7, first.size)
        // Never one artist twice in a row here: there are always others to put between.
        val artists = first.map { it.artist }
        assertTrue(artists.toString(), artists.windowed(2).none { it[0] == it[1] })
    }

    @Test
    fun `another random shuffles the same songs anew`() = runTest {
        val sentence = "jazz and ambient"
        val first = SentenceMix.build(api, session, sentence, { _, _ -> false })
        val again = (1..10).map { SentenceMix.build(api, session, sentence, { _, _ -> false }, random = Random(it)) }
        assertTrue(again.all { it.toSet() == first.toSet() })
        assertTrue(again.any { it != first })
    }

    @Test
    fun `nothing that matches gives nothing`() = runTest {
        assertEquals(emptyList<Any>(), SentenceMix.build(api, session, "zzz qqqq", { _, _ -> false }))
    }
}
