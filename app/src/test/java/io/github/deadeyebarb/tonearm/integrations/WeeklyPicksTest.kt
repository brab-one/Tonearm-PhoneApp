package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.weekly.WeeklyAlbum
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.weekly.WeeklySettings
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A week of AI picks against one fake host playing Navidrome, the Tonearm server and Lidarr. */
class WeeklyPicksTest {
    @get:Rule val tmp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()

    private class FakePlaylist(val id: String, var name: String, var comment: String? = null)
    private val playlists = mutableMapOf<String, FakePlaylist>()
    private val store = mutableMapOf<String, Pair<String, Long>>()
    /** The Tonearm server's answer to "recommendations". */
    private var picks = """{"picks":[],"madeAt":0,"running":true}"""
    private var refreshes = 0
    private var importListLooks = 0
    /** Tonearm's old Brainarr lists are still in Lidarr. */
    private var oldLists = true
    /** Method and path(+query) of every change sent to Lidarr. */
    private val lidarrChanges = mutableListOf<Pair<String, String>>()
    private val addedAlbums = mutableListOf<String>()
    /** The Tonearm server's answer to "picksfolder"; none: everyone's picks share the library. */
    private var picksFolder: String? = null
    /** Lidarr's artists. */
    private var artists = """[{"id":5,"artistName":"Tricky"}]"""

    private val T = 1_760_000_000_000L

    private fun subsonic(body: String = "") = MockResponse(body = """{"subsonic-response":{"status":"ok","version":"1.16.1"${if (body.isEmpty()) "" else ",$body"}}}""")

    private fun FakePlaylist.toJson() = buildJsonObject { put("id", id); put("name", name); comment?.let { put("comment", it) }; put("songCount", 0) }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@WeeklyPicksTest) { answer(request) }
        }
        server.start()
    }

    private fun answer(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        val q = { name: String -> url.queryParameter(name) }
        if (path.startsWith("/rest/")) return when (path.removePrefix("/rest/").removeSuffix(".view")) {
            "getPlaylists" -> subsonic(""""playlists":{"playlist":${JsonArray(playlists.values.map { it.toJson() })}}""")
            "getPlaylist" -> playlists[q("id")]?.let { subsonic(""""playlist":${it.toJson()}""") } ?: MockResponse(body = """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}""")
            "createPlaylist" -> {
                val p = FakePlaylist("pl${playlists.size + 1}", q("name")!!)
                playlists[p.id] = p
                subsonic(""""playlist":${p.toJson()}""")
            }
            "updatePlaylist" -> {
                playlists[q("playlistId")]?.let { p -> q("comment")?.let { p.comment = it }; q("name")?.let { p.name = it } }
                subsonic()
            }
            "deletePlaylist" -> { playlists.remove(q("id")); subsonic() }
            "getStarred2" -> subsonic(""""starred2":{}""")
            "search3" -> subsonic(""""searchResult3":{}""")
            else -> subsonic()
        }
        if (path.startsWith("/connect-tonearm/api/")) return when (path.substringAfterLast('/')) {
            "get" -> store[q("key")].let { MockResponse(body = """{"value":${it?.first?.let(::JsonPrimitive) ?: "null"},"version":${it?.second ?: 0}}""") }
            "put" -> {
                val current = store[q("key")]?.second ?: 0
                if (q("ifVersion")!!.toLong() != current) MockResponse(body = """{"ok":false,"conflict":true,"version":$current}""")
                else {
                    store[q("key")!!] = request.body!!.utf8() to current + 1
                    MockResponse(body = """{"ok":true,"conflict":false,"version":${current + 1}}""")
                }
            }
            "recommendations" -> { if (q("refresh") == "true") refreshes++; MockResponse(body = picks) }
            "picksfolder" -> picksFolder?.let { MockResponse(body = it) } ?: MockResponse(code = 404, body = """{"error":"no picks folders"}""")
            else -> MockResponse(code = 404)
        }
        val endpoint = path.removePrefix("/api/v1/")
        if (request.method != "GET") lidarrChanges += request.method to endpoint + (url.encodedQuery?.let { "?$it" } ?: "")
        return when {
            endpoint == "importlist" -> {
                importListLooks++
                if (!oldLists) MockResponse(body = "[]") else MockResponse(body = """[{"id":2,"name":"Brainarr AI Music Discovery","implementation":"Brainarr"},
                    {"id":3,"name":"Tonearm more like this","implementation":"Brainarr"},
                    {"id":4,"name":"Tonearm weekly picks","implementation":"Brainarr","fields":[{"name":"maxRecommendations","value":3}]}]""")
            }
            endpoint == "artist" -> MockResponse(body = artists)
            endpoint == "rootfolder" -> MockResponse(body = """[{"id":1,"path":"/music","defaultQualityProfileId":1,"defaultMetadataProfileId":1}]""")
            endpoint == "album/lookup" -> MockResponse(body = when (q("term")) {
                "Mazzy Star So Tonight That I Might See" -> """[{"id":0,"title":"So Tonight That I Might See","foreignAlbumId":"rg-m","artist":{"artistName":"Mazzy Star","foreignArtistId":"a-m"}}]"""
                "Tricky Maxinquaye" -> """[{"id":60,"title":"Maxinquaye","artistId":5,"monitored":false,"artist":{"artistName":"Tricky"}}]"""
                "Portishead Dummy" -> """[{"id":61,"title":"Dummy","artistId":6,"monitored":true,"artist":{"artistName":"Portishead"}}]"""
                "Björk Homogenic" -> """[{"id":0,"title":"Homogenic","foreignAlbumId":"rg-h","artist":{"artistName":"Björk"}}]"""
                else -> """[{"id":0,"title":"Something Else","artist":{"artistName":"Somebody"}}]"""
            })
            endpoint == "album" && request.method == "POST" -> MockResponse(
                body = request.body!!.utf8().also { addedAlbums += it }.let { sent ->
                    if ("rg-h" in sent) """{"id":51,"title":"Homogenic","artistId":10,"artist":{"artistName":"Björk"}}"""
                    else """{"id":50,"title":"So Tonight That I Might See","artistId":9,"artist":{"artistName":"Mazzy Star"}}"""
                },
            )
            else -> MockResponse(body = "{}")
        }
    }

    @After
    fun tearDown() = server.close()

    private val session by lazy { ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice"), "secret", OkHttpClient()) }
    private val config by lazy { LidarrConfig(url = server.url("/").toString().trimEnd('/'), keyEnc = "") }
    private val runFile by lazy { tmp.newFolder().resolve("weekly-run.json") }
    private val weekly by lazy {
        val http = IntegrationHttp(OkHttpClient()) { null }
        WeeklyPicks(SubsonicApi({ session }, json), LidarrClient(http, json), ConnectClient(http, json), json, "desk", runFile)
    }

    private fun batch() = playlists.values.single().let { WeeklyPicks.parse(Playlist(it.id, comment = it.comment)) }

    @Test
    fun `the old brainarr setup is taken over, tonearm's lists go and the first week starts`() = runTest {
        val news = weekly.tick(config, "k", session, now = T)
        assertEquals(WeeklySettings(on = true, albums = 3), weekly.settings(session))
        assertEquals(listOf("DELETE" to "importlist/3", "DELETE" to "importlist/4"), lidarrChanges)
        assertEquals("Picking this week's albums from what you play", news)
        assertEquals(1, refreshes)
        assertEquals(WeeklyState.RUNNING, batch()!!.status)
        assertTrue(runFile.exists())
        // Only once: the user's own Brainarr list is left alone from then on.
        weekly.tick(config, "k", session, now = T + 60_000)
        assertEquals(1, importListLooks)
        assertEquals(1, refreshes)
    }

    @Test
    fun `fresh picks become this week's albums in lidarr`() = runTest {
        weekly.tick(config, "k", session, now = T)
        // Still thinking: nothing happens yet.
        assertNull(weekly.tick(config, "k", session, now = T + 60_000))
        lidarrChanges.clear()
        picks = """{"running":false,"madeAt":${T + 120_000},"picks":[
            {"artist":"Portishead","album":"Dummy"},{"artist":"Nobody","album":"Nothing"},
            {"artist":"Mazzy Star","album":"So Tonight That I Might See"},{"artist":"Tricky","album":"Maxinquaye"},
            {"artist":"Björk","album":"Homogenic"},{"artist":"Mazzy Star","album":"Seasons of Your Day"}]}"""
        val news = weekly.tick(config, "k", session, now = T + 180_000)
        // 3 a week (taken over from the old list): Dummy is already wanted and Nothing is unknown, so they're skipped.
        assertEquals("This week's picks: 3 albums are downloading", news)
        val state = batch()!!
        assertEquals(WeeklyState.READY, state.status)
        assertEquals(
            listOf(
                WeeklyAlbum(50, "So Tonight That I Might See", "Mazzy Star", 9, newArtist = true, songs = emptyList()),
                WeeklyAlbum(60, "Maxinquaye", "Tricky", 5, newArtist = false, songs = emptyList()),
                WeeklyAlbum(51, "Homogenic", "Björk", 10, newArtist = true, songs = emptyList()),
            ),
            state.albums,
        )
        assertEquals(1, lidarrChanges.count { it == "PUT" to "album/monitor" })
        assertEquals(2, lidarrChanges.count { it == "POST" to "album" })
        assertFalse(runFile.exists())
    }

    @Test
    fun `weekly picks are on until switched off`() = runTest {
        oldLists = false
        assertEquals("Picking this week's albums from what you play", weekly.tick(config, "k", session, now = T))
        assertEquals(WeeklySettings(on = true), weekly.settings(session))
        weekly.saveSettings(session, WeeklySettings(on = false))
        assertEquals(false, weekly.settings(session).on)
    }

    @Test
    fun `a failed ai run gives up on the week`() = runTest {
        weekly.tick(config, "k", session, now = T)
        picks = """{"running":false,"madeAt":0,"picks":[],"problem":"Ollama: timed out"}"""
        val news = weekly.tick(config, "k", session, now = T + 60_000)
        assertEquals("This week's picks didn't work: Ollama: timed out", news)
        assertTrue(playlists.isEmpty())
        assertFalse(runFile.exists())
    }

    @Test
    fun `state round-trips through the playlist comment, also from brainarr's weeks`() {
        val state = WeeklyState(created = T, by = "phone", status = WeeklyState.READY,
            albums = listOf(WeeklyAlbum(7, "OK Computer", "Radiohead", 3, newArtist = true)))
        val comment = WeeklyPicks.comment(state)
        assertTrue(comment.startsWith("AI picks for the week of"))
        assertEquals(state, WeeklyPicks.parse(Playlist("p1", comment = comment)))
        val old = "Brainarr's picks for the week of 1 Oct. Deleted when next week's arrive, unless you like this playlist.\n" +
            WeeklyPicks.MARKER + comment.substringAfter(WeeklyPicks.MARKER)
        assertEquals(state, WeeklyPicks.parse(Playlist("p0", comment = old)))
        assertNull(WeeklyPicks.parse(Playlist("p2", comment = "Kept from the weekly picks of 1 Oct")))
        assertNull(WeeklyPicks.parse(Playlist("p3")))
    }


    private val week = """{"running":false,"madeAt":${T + 120_000},"picks":[
        {"artist":"Portishead","album":"Dummy"},{"artist":"Mazzy Star","album":"So Tonight That I Might See"},
        {"artist":"Tricky","album":"Maxinquaye"},{"artist":"Björk","album":"Homogenic","songs":["Jóga","Bachelorette"]}]}"""

    @Test
    fun `with a folder of their own, picks go there and leave out artists that live elsewhere`() = runTest {
        oldLists = false
        picksFolder = """{"path":"/picks/alice","ready":true}"""
        // Tricky is in the shared library and Mazzy Star in someone else's picks: an album goes where its artist is.
        artists = """[{"id":5,"artistName":"Tricky","path":"/music/Tricky"},{"id":7,"artistName":"Mazzy Star","foreignArtistId":"a-m","path":"/picks/bob/Mazzy Star"}]"""
        weekly.tick(config.copy(limited = true), "k", session, now = T)
        picks = week
        assertEquals("This week's picks: 1 albums are downloading", weekly.tick(config.copy(limited = true), "k", session, now = T + 180_000))
        assertTrue("\"rootFolderPath\":\"/picks/alice\"" in addedAlbums.single())
        assertEquals(listOf("Jóga", "Bachelorette"), batch()!!.albums.single().songs)
        // Not an admin: Tonearm's old Brainarr lists aren't theirs to look at.
        assertEquals(0, importListLooks)
    }

    @Test
    fun `a picks folder that isn't set up yet holds the week back`() = runTest {
        oldLists = false
        picksFolder = """{"path":"/picks/alice","ready":false,"problem":"Lidarr can't use /picks/alice yet (Path does not exist)."}"""
        assertEquals(
            "Weekly picks wait for your own picks folder: Lidarr can't use /picks/alice yet (Path does not exist).",
            weekly.tick(config, "k", session, now = T),
        )
        assertTrue(playlists.isEmpty())
        assertEquals(0, refreshes)
        // The weekly picks screen shows why.
        assertEquals("Weekly picks wait for your own picks folder: Lidarr can't use /picks/alice yet (Path does not exist).", weekly.waiting())
    }

    @Test
    fun `the week's playlist is the albums' standout songs, taking turns`() {
        fun song(id: String, title: String) = io.github.deadeyebarb.tonearm.subsonic.Song(id, title = title)
        val homogenic = WeeklyAlbum(51, "Homogenic", "Björk", 10, newArtist = true, songs = listOf("Jóga", "Bachelorette (Remastered)"))
        val dummy = WeeklyAlbum(61, "Dummy", "Portishead", 6, newArtist = true, songs = listOf("A Song It Doesn't Have"))
        val playlist = WeeklyPicks.playlistOf(listOf(
            homogenic to listOf(song("h1", "Hunter"), song("h2", "Jóga"), song("h3", "Bachelorette")),
            dummy to listOf(song("d1", "Mysterons"), song("d2", "Sour Times"), song("d3", "Strangers"), song("d4", "It Could Be Sweet")),
        ))
        // Björk's named songs, Portishead's first three since the AI named one it doesn't have; the albums take turns.
        assertEquals(listOf("h2", "d1", "h3", "d2", "d3"), playlist.map { it.id })
        // Weeks from before keep whole albums.
        assertEquals(4, WeeklyPicks.playlistOf(listOf(dummy.copy(songs = null) to playlist.take(4))).size)
    }

    @Test
    fun `without picks folders, only admins get weekly picks`() = runTest {
        oldLists = false
        assertEquals(
            "Weekly picks need a picks folder of your own on the Tonearm server (PICKS_FOLDER)",
            weekly.tick(config.copy(limited = true), "k", session, now = T),
        )
        assertTrue(playlists.isEmpty())
        // An admin's go (after the half-day pause between tries) starts the week.
        assertEquals("Picking this week's albums from what you play", weekly.tick(config, "k", session, now = T + 13 * 3_600_000L))
    }
}
