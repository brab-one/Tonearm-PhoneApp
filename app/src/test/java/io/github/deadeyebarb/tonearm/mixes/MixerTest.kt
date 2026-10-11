package io.github.deadeyebarb.tonearm.mixes

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.connect.Mix
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.NextSong
import io.github.deadeyebarb.tonearm.likes.Dislikes
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** Radio and mixes against one fake host playing Navidrome and the Tonearm server. */
class MixerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private val now = System.currentTimeMillis()
    private val day = 24 * 3600_000L

    /** The Tonearm server's hello: which of its features are there. */
    private var hello = """{"server":"tonearm","protocol":2,"dislikes":true}"""
    private var radioAnswer: MockResponse = MockResponse(code = 404, body = """{"error":"not here"}""")
    /** Answers to "mix" and "dailymixes", in turn; the last one stays. */
    private val mixAnswers = ArrayDeque<String>()
    /** What "mix" gets instead, when set: the server down, say. */
    private var mixFailure: MockResponse? = null
    private val dailyAnswers = ArrayDeque<String>()
    private var disliked = """{"songs":[{"artist":"Signal Garden","title":"Shimmer"}],"artists":[]}"""

    private class FakePlaylist(val id: String, val owner: String, val comment: String?, val songs: List<String>)
    private val playlists = mutableListOf<FakePlaylist>()
    /** Song id to when it was liked. */
    private val starred = mutableMapOf<String, Long>()
    /** Every request. */
    private val requests = mutableListOf<HttpUrl>()
    private val bodies = mutableMapOf<String, String>()

    private fun song(id: String, title: String = id, artist: String = "Artist $id", genre: String? = null) =
        buildJsonObject { put("id", id); put("title", title); put("artist", artist); genre?.let { put("genre", it) } }

    private val seed = Song("al1-2", title = "Middle C", artist = "The Oscillators")
    private val catalog = mapOf(
        "al1-1" to song("al1-1", "Low Hum", "The Oscillators"),
        "al1-2" to song("al1-2", "Middle C", "The Oscillators"),
        "al3-1" to song("al3-1", "Deep Drone", "Signal Garden", "Ambient"),
        "al3-2" to song("al3-2", "Shimmer", "Signal Garden", "Ambient"),
        "al4-1" to song("al4-1", "Slow Rain", "Midnight Quartet", "Jazz"),
        "al4-2" to song("al4-2", "Late Set", "Midnight Quartet", "Jazz"),
        "al4-3" to song("al4-3", "Brushes", "Midnight Quartet", "Jazz"),
    )

    /** Navidrome's similar songs: six of its own for every song, by six artists. */
    private fun similarTo(id: String) = (1..6).map { song("$id-like-$it", artist = "Like $it") }

    private fun subsonic(body: String = "") = MockResponse(body = """{"subsonic-response":{"status":"ok","version":"1.16.1"${if (body.isEmpty()) "" else ",$body"}}}""")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@MixerTest) { answer(request) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun answer(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        val q = { name: String -> url.queryParameter(name) }
        requests += url
        request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { bodies[path] = it }
        if (path.startsWith("/rest/")) return when (path.removePrefix("/rest/").removeSuffix(".view")) {
            "getPlaylists" -> subsonic(""""playlists":{"playlist":${JsonArray(playlists.map { p -> buildJsonObject { put("id", p.id); put("name", p.id); put("owner", p.owner); p.comment?.let { put("comment", it) }; put("songCount", p.songs.size) } })}}""")
            "getPlaylist" -> playlists.first { it.id == q("id") }.let { p -> subsonic(""""playlist":{"id":"${p.id}","owner":"${p.owner}","entry":${JsonArray(p.songs.map { catalog[it] ?: song(it) })}}""") }
            "getStarred2" -> subsonic(""""starred2":{"song":${JsonArray(starred.map { (id, at) -> buildJsonObject { (catalog[id] ?: song(id)).forEach { (k, v) -> put(k, v) }; put("starred", Instant.ofEpochMilli(at).toString()) } })}}""")
            "getSimilarSongs2" -> subsonic(""""similarSongs2":{"song":${JsonArray(similarTo(q("id")!!))}}""")
            "getGenres" -> subsonic(""""genres":{"genre":[{"value":"Jazz","songCount":3},{"value":"Ambient","songCount":2}]}""")
            "getRandomSongs" -> subsonic(""""randomSongs":{"song":${JsonArray(catalog.values.filter { q("genre") == null || it["genre"]?.jsonPrimitive?.content == q("genre") })}}""")
            "getArtists" -> subsonic(""""artists":{"index":[{"name":"M","artist":[{"id":"ar3","name":"Midnight Quartet"}]}]}""")
            "getTopSongs" -> subsonic(""""topSongs":{"song":${JsonArray(catalog.values.filter { it["artist"]?.jsonPrimitive?.content == q("artist") })}}""")
            "search3" -> subsonic(""""searchResult3":{"song":${JsonArray(catalog.values.filter { q("query")!!.lowercase() in it["title"]!!.jsonPrimitive.content.lowercase() })}}""")
            else -> subsonic()
        }
        if (path.startsWith("/connect-tonearm/api/")) return when (path.substringAfterLast('/')) {
            "hello" -> MockResponse(body = hello)
            "disliked" -> MockResponse(body = disliked)
            "radio" -> radioAnswer
            "mix" -> mixFailure ?: MockResponse(body = if (mixAnswers.size > 1) mixAnswers.removeFirst() else mixAnswers.first())
            "dailymixes" -> MockResponse(body = if (dailyAnswers.size > 1) dailyAnswers.removeFirst() else dailyAnswers.first())
            else -> MockResponse(code = 404, body = """{"error":"no such op"}""")
        }
        return MockResponse(code = 404)
    }

    private val session by lazy { ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice"), "secret", OkHttpClient()) }

    private suspend fun mixer(): Mixer {
        val http = IntegrationHttp(OkHttpClient()) { null }
        val client = ConnectClient(http, json)
        val router = ConnectRouter(client)
        val api = SubsonicApi({ session }, json)
        val dislikes = Dislikes(client, router) { session }
        dislikes.refresh()
        val weekly = WeeklyPicks(api, LidarrClient(http, json), client, json, "test-device", java.io.File(tmp.root, "weekly-run.json"))
        return Mixer(api, client, router, YouTubeMusic(OkHttpClient()), dislikes, weekly)
    }

    private fun requested(op: String) = synchronized(this) { requests.filter { it.encodedPath.endsWith("/$op") || it.encodedPath.endsWith("/$op.view") } }
    private fun queueOf(vararg ids: String) = ids.map { NextSong(Song(it, title = it), false) }
    private fun ids(songs: List<NextSong>) = songs.map { it.song.id }

    private fun excluded() = json.parseToJsonElement(bodies["/connect-tonearm/api/radio"]!!).jsonObject["exclude"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the server's radio is used when it has one, without disliked or queued songs`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"dislikes":true,"radio":true}"""
        radioAnswer = MockResponse(body = """{"title":"Radio · Middle C","songs":[${catalog["al3-1"]},${catalog["al3-2"]},${catalog["al1-1"]},${catalog["al4-1"]}],"more":true,"problem":null}""")
        val result = mixer().radio(session, RadioSeed(RadioSeed.SONG, seed), queueOf("al1-1", "al1-2"), allowYouTube = false)
        assertEquals("Radio · Middle C", result.title)
        // Low Hum played before the seed: left out, and the seed's key tells the server not to steer by it.
        assertEquals(listOf("al3-1", "al4-1"), ids(result.songs))
        assertTrue(result.more)
        val asked = requested("radio").single()
        assertEquals(listOf("song", "al1-2", "lib/al1-2", "25", null), listOf("seed", "id", "key", "count", "artist").map(asked::queryParameter))
        assertEquals(listOf("lib/al1-1", "lib/al1-2"), excluded())
        assertTrue(requested("getSimilarSongs2").isEmpty())
    }

    @Test
    fun `the whole queue is left out, and a song radio's key says where the seed is in it`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"radio":true}"""
        radioAnswer = MockResponse(body = """{"title":"Radio · Middle C","songs":[${catalog["al3-1"]}],"more":true}""")
        val mixer = mixer()
        // Shuffle all played three other songs before it: they don't come again, but only the seed on steers.
        val before = queueOf("al4-1", "al3-2", "al4-2")
        mixer.radio(session, RadioSeed(RadioSeed.SONG, seed), before + queueOf("al1-2", "al3-1"), false)
        assertEquals(listOf("lib/al4-1", "lib/al3-2", "lib/al4-2", "lib/al1-2", "lib/al3-1"), excluded())
        assertEquals("lib/al1-2", requested("radio").last().queryParameter("key"))
        // A YouTube Music seed and a file on the phone by their own keys; the picks radio has none.
        val youtube = Song("dQw4w9WgXcQ", title = "Rewrite", artist = "Sia")
        mixer.radio(session, RadioSeed(RadioSeed.SONG, youtube, youtube = true), before + NextSong(youtube, true), false)
        assertEquals(listOf("lib/al4-1", "lib/al3-2", "lib/al4-2", "yt/dQw4w9WgXcQ"), excluded())
        assertEquals("yt/dQw4w9WgXcQ", requested("radio").last().queryParameter("key"))
        mixer.radio(session, RadioSeed(RadioSeed.SONG, Song("1234", title = "Chandelier", artist = "Sia"), local = true), before, false)
        assertEquals("lib/1234", requested("radio").last().queryParameter("key"))
        mixer.radio(session, RadioSeed(RadioSeed.PICKS), before, false)
        assertEquals(listOf("lib/al4-1", "lib/al3-2", "lib/al4-2"), excluded())
        assertNull(requested("radio").last().queryParameter("key"))
    }

    @Test
    fun `a youtube music song or a file is a seed by artist and title`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"radio":true}"""
        radioAnswer = MockResponse(body = """{"title":"Radio · Rewrite","songs":[${catalog["al4-1"]}],"more":true}""")
        val mixer = mixer()
        mixer.radio(session, RadioSeed(RadioSeed.SONG, Song("dQw4w9WgXcQ", title = "Rewrite", artist = "Sia"), youtube = true), emptyList(), false)
        mixer.radio(session, RadioSeed(RadioSeed.SONG, Song("1234", title = "Chandelier", artist = "Sia"), local = true), emptyList(), false)
        val (youtube, file) = requested("radio")
        assertEquals(listOf("Sia", "Rewrite", null), listOf("artist", "title", "id").map(youtube::queryParameter))
        assertEquals(listOf("Sia", "Chandelier", null), listOf("artist", "title", "id").map(file::queryParameter))
    }

    @Test
    fun `when the server's radio fails or has nothing, songs like the seed are made here`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"dislikes":true,"radio":true}"""
        for (failure in listOf(
            MockResponse(code = 404, body = """{"error":"seed has to be song or picks"}"""),
            MockResponse(code = 502, body = "Bad gateway"),
            MockResponse(body = "not json"),
            MockResponse(body = """{"title":"Radio · Middle C","songs":[],"more":false,"problem":"Nothing like that in your library"}"""),
        )) {
            radioAnswer = failure
            val result = mixer().radio(session, RadioSeed(RadioSeed.SONG, seed), queueOf("al1-2"), allowYouTube = false)
            assertEquals("Radio · Middle C", result.title)
            assertEquals((1..6).map { "al1-2-like-$it" }, ids(result.songs))
            assertTrue(result.more)
            assertNull(result.problem)
        }
        assertEquals(4, requested("radio").size)
    }

    @Test
    fun `without the server's radio it isn't asked, and every other batch follows the last song queued`() = runTest {
        val mixer = mixer()
        val first = mixer.radio(session, RadioSeed(RadioSeed.SONG, seed), queueOf("al1-1", "al1-2"), false)
        val queue = queueOf("al1-1", "al1-2") + first.songs
        mixer.radio(session, RadioSeed(RadioSeed.SONG, seed), queue, false)
        mixer.radio(session, RadioSeed(RadioSeed.SONG, seed), queue, false)
        assertTrue(requested("radio").isEmpty())
        // The third batch's turn is the seed's, whose songs are all queued: the last song queued fills in.
        val last = first.songs.last().song.id
        assertEquals(listOf("al1-2", last, "al1-2", last), requested("getSimilarSongs2").map { it.queryParameter("id") })
        // What's queued already doesn't come again.
        assertTrue(ids(mixer.radio(session, RadioSeed(RadioSeed.SONG, seed), queue, false).songs).none { it in ids(queue) })
    }

    private fun week(id: String, created: Long, status: String = WeeklyState.READY, owner: String = "alice", vararg songs: String) =
        FakePlaylist(id, owner, WeeklyPicks.comment(WeeklyState(created = created, by = "desk", status = status)), songs.toList())

    @Test
    fun `picks radio made here starts from the newest ready weekly picks of your own and songs liked lately`() = runTest {
        playlists += week("wk-old", now - 14 * day, songs = arrayOf("p-old"))
        playlists += week("wk-new", now - 7 * day, songs = arrayOf("p1", "p2"))
        playlists += week("wk-run", now - day, WeeklyState.RUNNING, songs = arrayOf("p-run"))
        playlists += week("wk-bob", now - 3600_000, owner = "bob", songs = arrayOf("p-bob"))
        starred["l-old"] = now - 90 * day
        starred["l2"] = now - 20 * day
        starred["l1"] = now - 2 * day
        val mixer = mixer()
        val first = mixer.radio(session, RadioSeed(RadioSeed.PICKS), emptyList(), false)
        assertEquals("Picks radio", first.title)
        assertTrue(first.more)
        val songs = ids(first.songs)
        assertEquals(24, songs.size)
        assertTrue(songs.containsAll(listOf("p1", "p2", "l1", "l2")))
        assertTrue(songs.none { it.startsWith("p-") || it.startsWith("l-old") })
        assertEquals(listOf("l1", "l2", "p1", "p2"), requested("getSimilarSongs2").mapNotNull { it.queryParameter("id") }.sorted())
        // The seeds come in order: picks, then likes newest first.
        assertEquals(listOf("p1", "p2", "l1", "l2"), songs.filter { it in setOf("p1", "p2", "l1", "l2") })

        // Every seed queued: songs like them keep it going.
        val next = mixer.radio(session, RadioSeed(RadioSeed.PICKS), first.songs, false)
        assertEquals(4, next.songs.size)
        assertTrue(ids(next.songs).none { it in songs })
    }

    @Test
    fun `picks radio without picks or likes says what it needs`() = runTest {
        starred["l-old"] = now - 90 * day
        val result = mixer().radio(session, RadioSeed(RadioSeed.PICKS), emptyList(), false)
        assertEquals(emptyList<NextSong>(), result.songs)
        assertFalse(result.more)
        assertEquals(Mixer.NO_PICKS, result.problem)
        assertEquals(Mixer.NO_PICKS, mixer().radio(null, RadioSeed(RadioSeed.PICKS), emptyList(), false).problem)
    }

    @Test
    fun `a mix from a sentence asks again while the ai works`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"dislikes":true,"mix":true}"""
        val running = """{"id":"mix-1","prompt":"mellow jazz","title":"","songs":[],"running":true,"ai":"Ollama gemma4:26b"}"""
        mixAnswers += listOf(running, running, """{"id":"mix-1","prompt":"mellow jazz","title":"Mellow Jazz","songs":[${catalog["al4-1"]},${catalog["al3-2"]}],"running":false,"ai":"Ollama gemma4:26b"}""")
        val waits = mutableListOf<Mix>()
        val mix = mixer().prompt(session, "mellow jazz", refresh = true) { waits += it }
        assertEquals("Mellow Jazz", mix.title)
        assertEquals("Ollama gemma4:26b", mix.ai)
        assertEquals(listOf("al4-1"), mix.songs.map { it.id })
        assertEquals(2, waits.size)
        assertEquals(listOf("true", null, null), requested("mix").map { it.queryParameter("refresh") })
        assertTrue(requested("getRandomSongs").isEmpty())
    }

    @Test
    fun `when the ai can't answer, the mix is matched in the library with its problem as a note`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"dislikes":true,"mix":true}"""
        mixAnswers += """{"id":"mix-1","prompt":"mellow jazz","title":"","songs":[],"running":false,"problem":"The AI couldn't answer: timed out","ai":"Ollama gemma4:26b"}"""
        val mix = mixer().prompt(session, "mellow jazz")
        assertNull(mix.ai)
        assertEquals("mellow jazz", mix.title)
        assertEquals("mellow jazz", mix.prompt)
        assertEquals("The AI couldn't answer: timed out", mix.problem)
        assertEquals(setOf("al4-1", "al4-2", "al4-3"), mix.songs.map { it.id }.toSet())

        mixAnswers.clear()
        mixAnswers += """{"error":"This Tonearm server has no AI set up"}"""
        val failed = mixer().prompt(session, "mellow jazz")
        assertEquals(3, failed.songs.size)
        val problem = failed.problem!!
        assertTrue(problem, "no AI" in problem)
    }

    @Test
    fun `when the server doesn't answer, the note says so plainly`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"dislikes":true,"mix":true}"""
        // The proxy in front of it, with nothing behind it.
        mixFailure = MockResponse(code = 502, body = "<html><body><h1>502 Bad Gateway</h1></body></html>")
        val mix = mixer().prompt(session, "mellow jazz")
        assertNull(mix.ai)
        assertEquals(Mixer.SERVER_UNREACHABLE, mix.problem)
        assertEquals(setOf("al4-1", "al4-2", "al4-3"), mix.songs.map { it.id }.toSet())
    }

    @Test
    fun `without the server's ai the mix goes straight to the library`() = runTest {
        val mix = mixer().prompt(session, "something with Midnight Quartet")
        assertTrue(requested("mix").isEmpty())
        assertNull(mix.ai)
        assertNull(mix.problem)
        assertEquals(setOf("al4-1", "al4-2", "al4-3"), mix.songs.map { it.id }.toSet())
        assertEquals("Nothing in your library matches “zzz”", mixer().prompt(session, "zzz").problem)
    }

    @Test
    fun `asking again for a mix matched in the library shuffles it anew`() = runTest {
        val mixer = mixer()
        val first = mixer.prompt(session, "mellow jazz").songs.map { it.id }
        assertEquals(first, mixer.prompt(session, "mellow jazz").songs.map { it.id })
        // Three songs: ten asks all in the first order would be one in 60 million.
        val again = (1..10).map { mixer.prompt(session, "mellow jazz", refresh = true).songs.map { it.id } }
        assertTrue(again.all { it.toSet() == first.toSet() })
        assertTrue(again.any { it != first })
        assertTrue(requested("getRandomSongs").all { it.queryParameter("genre") == "Jazz" })
    }

    @Test
    fun `an ai that's still at it after five minutes is a failure to try again later`() = runTest {
        hello = """{"server":"tonearm","protocol":2,"mix":true}"""
        mixAnswers += """{"id":"mix-1","prompt":"x","songs":[],"running":true}"""
        try {
            mixer().prompt(session, "mellow jazz")
            fail("expected failure")
        } catch (e: IOException) {
            assertTrue(e.message!!, "try again" in e.message!!)
        }
        assertEquals(101, requested("mix").size)
    }

    @Test
    fun `daily mixes come only from the server, asked again while they're made`() = runTest {
        assertNull(mixer().daily(session, ZoneId.of("Europe/Vienna")))
        assertTrue(requested("dailymixes").isEmpty())

        hello = """{"server":"tonearm","protocol":2,"dislikes":true,"dailyMixes":true}"""
        dailyAnswers += listOf(
            """{"day":"2026-10-09","running":true,"mixes":[]}""",
            """{"day":"2026-10-09","running":false,"mixes":[{"id":"daily-2026-10-09-1","title":"Daily Mix 1","songs":[${catalog["al3-1"]},${catalog["al3-2"]}]}]}""",
        )
        var waited = 0
        val daily = mixer().daily(session, ZoneId.of("Europe/Vienna")) { waited++ }!!
        assertEquals(1, waited)
        assertEquals(listOf("al3-1"), daily.mixes.single().songs.map { it.id })
        assertTrue(requested("dailymixes").all { it.queryParameter("tz") == "Europe/Vienna" })
    }
}
