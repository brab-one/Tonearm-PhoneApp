package io.github.deadeyebarb.tonearm.connect

import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.Integrations
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
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectClientTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    private lateinit var client: ConnectClient

    @Before
    fun setUp() {
        server.start()
        client = ConnectClient(IntegrationHttp(OkHttpClient()) { null }, json)
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun session(auth: AuthMethod = AuthMethod.TOKEN, username: String = "alice") =
        ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = username, auth = auth), "wonderland", OkHttpClient())

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
    fun `publishing posts the state as the body`() = runTest {
        respond("""{"ok":true,"seq":0}""")
        val state = DeviceState("desktop-1", "Desk", "desktop", "https://music.example", PlaybackState(playing = true, index = 0, queue = listOf(ConnectSong("s1", title = "Song"))))
        client.publish(ConnectRoute.Server(session()), state)
        val request = server.takeRequest()
        assertEquals("/connect-tonearm/api/publish", request.url.encodedPath)
        assertEquals("desktop-1", request.url.queryParameter("device"))
        assertEquals(state, json.decodeFromString(DeviceState.serializer(), request.body!!.utf8()))
    }

    @Test
    fun `devices and polled commands are read from the server's answers`() = runTest {
        respond("""{"devices":[{"id":"desktop-1","online":true,"secondsSinceSeen":3,"state":"{\"id\":\"desktop-1\",\"name\":\"Desk\",\"kind\":\"desktop\"}"},{"id":"junk","online":false,"secondsSinceSeen":9,"state":"not json"}]}""")
        val devices = client.devices(ConnectRoute.Server(session()))
        assertEquals(listOf("Desk"), devices.map { it.state.name })
        assertTrue(devices.single().online)

        respond("""{"commands":[{"seq":7,"from":"phone-1","payload":"{\"type\":\"seek\",\"positionMs\":90000}"}],"seq":7}""")
        val (commands, seq) = client.poll(ConnectRoute.Server(session()), "desktop-1", after = 5, waitSeconds = 20)
        assertEquals(7L, seq)
        assertEquals(ConnectCommand(ConnectCommand.SEEK, positionMs = 90_000), commands.single().command)
        assertEquals("phone-1", commands.single().from)
        val request = server.takeRequest().let { server.takeRequest() }
        assertEquals("5", request.url.queryParameter("after"))
        assertEquals("20", request.url.queryParameter("wait"))
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
            assertEquals("Navidrome didn't accept that login", e.reason)
        }
    }

    @Test
    fun `a proxy with nothing behind it gives no reason of the server's`() = runTest {
        respond("<html><body>502 Bad Gateway</body></html>", 502)
        try {
            client.devices(ConnectRoute.Server(session()))
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals(502, e.code)
            assertEquals("Tonearm server: No answer through the proxy (HTTP 502); try again in a moment", e.message)
            assertNull(e.reason)
        }
    }

    @Test
    fun `the router finds the tonearm server and remembers it`() = runTest {
        val router = ConnectRouter(client)
        respond("""{"server":"tonearm","protocol":2,"version":"1.0.0","user":"alice"}""")
        assertTrue(router.route(session()) is ConnectRoute.Server)
        assertTrue(router.route(session()) is ConnectRoute.Server)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `what one user's tonearm server offers isn't kept for another whose lookup fails`() = runTest {
        val router = ConnectRouter(client)
        respond("""{"server":"tonearm","protocol":2,"user":"alice","lidarr":true,"lidarrAdmin":true}""")
        val alice = router.refresh(session())
        assertTrue(alice!!.lidarrAdmin)
        assertEquals(alice, router.server.value)
        // Bob on the same server, at a bad moment: alice's Lidarr isn't his.
        respond("""{"error":"busy"}""", 503)
        assertEquals(null, router.refresh(session(username = "bob")))
        assertEquals(null, router.server.value)
        // Back to alice: what was found for her is hers again.
        assertEquals(alice, router.refresh(session()))
        assertEquals(alice, router.server.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the router tells what each lookup found, none too, but not one that failed`() = runTest {
        val found = mutableListOf<Pair<String, Boolean?>>()
        val router = ConnectRouter(client) { key, info -> found += key to info?.resume }
        respond("""{"server":"tonearm","protocol":2,"user":"alice","resume":true}""")
        router.refresh(session())
        // Remembered: not looked up again.
        router.refresh(session())
        respond("""{"error":"busy"}""", 503)
        router.refresh(session(username = "bob"))
        respond("404 page not found", 404)
        router.refresh(session(username = "carol"))
        assertEquals(listOf(ConnectRouter.keyOf(session()) to true, ConnectRouter.keyOf(session(username = "carol")) to null), found)
    }

    @Test
    fun `without a tonearm server connect isn't available`() = runTest {
        // Navidrome's answer for a page it doesn't have.
        respond("404 page not found", 404)
        try {
            ConnectRouter(client).route(session())
            error("expected failure")
        } catch (_: ConnectUnavailableException) {
        }
        respond("<!doctype html><html></html>")
        assertEquals(null, client.findServer(session()))
    }

    @Test
    fun `a tonearm server that rejects the login says so`() = runTest {
        respond("""{"error":"Navidrome didn't accept that login"}""", 401)
        try {
            ConnectRouter(client).route(session())
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
        http.newCall(okhttp3.Request.Builder().url(server.url("/connect-tonearm/api/listening")).build()).execute().close()
        http.newCall(okhttp3.Request.Builder().url(server.url("/rest/ping.view")).build()).execute().close()
        val (lidarr, api, other) = List(3) { server.takeRequest().url }
        assertEquals("alice", lidarr.queryParameter("u"))
        assertEquals(lidarr.queryParameter("s"), api.queryParameter("s"))
        assertEquals(SubsonicAuth.token("wonderland", lidarr.queryParameter("s")!!), lidarr.queryParameter("t"))
        assertEquals(null, other.queryParameter("u"))
    }

    @Test
    fun `the server's lidarr replaces the app's own, keeping its request preferences`() {
        val stored = Integrations(lidarr = LidarrConfig(url = "http://lidarr.lan:8686", keyEnc = "secret", rootFolderPath = "/music", monitor = "latest"))
        val info = TonearmServerInfo(baseUrl = "https://music.example/", lidarr = true, lidarrAdmin = false)
        val used = stored.through(info)
        assertEquals("https://music.example/connect-tonearm/lidarr", used.lidarr!!.url)
        assertEquals("", used.lidarr!!.keyEnc)
        assertEquals("/music", used.lidarr!!.rootFolderPath)
        assertEquals("latest", used.lidarr!!.monitor)
        assertTrue(used.lidarr!!.viaServer && used.lidarr!!.limited)
        // Lidarr only comes through the server.
        assertEquals(Integrations(), stored.through(null))
        assertEquals(null, stored.through(info.copy(lidarr = false)).lidarr)
    }

    @Test
    fun `plays go to the server's history and come back as listening`() = runTest {
        respond("""{"server":"tonearm","protocol":2,"history":true,"ai":"Claude claude-opus-5-5"}""")
        val info = client.findServer(session())!!
        assertTrue(info.history)
        assertEquals("Claude claude-opus-5-5", info.ai)
        server.takeRequest()
        respond("""{"ok":true,"added":1}""")
        client.played(session(), listOf(Played(5, "Sia", "Rewrite", durationMs = 200_000, listenedMs = 150_000, source = "youtube")))
        val sent = server.takeRequest()
        assertEquals("/connect-tonearm/api/played", sent.url.encodedPath)
        val play = Json.parseToJsonElement(sent.body!!.utf8()).jsonArray.single().jsonObject
        assertEquals("youtube", play["source"]!!.jsonPrimitive.content)
        assertEquals(150_000L, play["listenedMs"]!!.jsonPrimitive.long)
        respond("""{"since":1,"plays":3,"artists":[{"artist":"Sia","plays":3,"skips":1,"lastPlayed":9}],"recent":[{"artist":"Sia","title":"Rewrite","at":9}]}""")
        val listening = client.listening(session(), days = 30, artists = 10, recent = 5)
        assertEquals(ArtistCount("Sia", 3, 1, 9), listening.artists.single())
        assertEquals("Rewrite", listening.recent.single().title)
        assertEquals("30", server.takeRequest().url.queryParameter("days"))
        respond("""{"ok":true}""")
        client.dismiss(session(), "Mazzy Star")
        val dismissed = server.takeRequest().url
        assertEquals("/connect-tonearm/api/dismiss", dismissed.encodedPath)
        assertEquals("Mazzy Star", dismissed.queryParameter("artist"))
        assertEquals(null, dismissed.queryParameter("album"))
    }

    @Test
    fun `discovery picks and similar artists come from the tonearm server`() = runTest {
        respond("""{"server":"tonearm","protocol":2,"discovery":true,"recommendations":false}""")
        assertTrue(client.findServer(session())!!.discovery)
        respond("""{"picks":[{"artist":"Björk","album":"Homogenic","year":1997,"because":["Radiohead","Portishead"]}],"madeAt":5}""")
        val picks = client.discover(session(), refresh = true)
        assertEquals(DiscoveryPick("Björk", "Homogenic", 1997, because = listOf("Radiohead", "Portishead")), picks.picks.single())
        assertEquals("true", server.takeRequest().let { server.takeRequest() }.url.queryParameter("refresh"))
        respond("""{"artist":"Portishead","similar":[{"artist":"Massive Attack","inLibrary":true},{"artist":"Tricky","imageUrl":"https://img/t.jpg"}]}""")
        val similar = client.similarArtists(session(), "Portishead")
        assertEquals(listOf("Massive Attack" to true, "Tricky" to false), similar.map { it.artist to it.inLibrary })
        assertEquals("Portishead", server.takeRequest().url.queryParameter("artist"))
    }

    @Test
    fun `radio sends the seed and its key in the query and the queue as the body, and gives the server's subsonic songs`() = runTest {
        respond("""{"title":"Radio · Middle C","songs":[{"id":"al2-1","title":"Forty-Four","artist":"The Oscillators","size":4096,"suffix":"flac",
            "replayGain":{"trackGain":-4.0,"albumGain":-4.5}}],"more":true,"problem":null}""")
        val batch = client.radio(session(), "song", id = "al1-2", key = "lib/al1-2", exclude = listOf("lib/al1-1", "lib/al1-2", "yt/dQw4w9WgXcQ"), count = 5)
        assertEquals("Radio · Middle C", batch.title)
        assertTrue(batch.more)
        assertEquals(4096L, batch.songs.single().size)
        assertEquals(-4.0f, batch.songs.single().replayGain!!.trackGain)
        val request = server.takeRequest()
        assertEquals("/connect-tonearm/api/radio", request.url.encodedPath)
        assertEquals("song", request.url.queryParameter("seed"))
        assertEquals("al1-2", request.url.queryParameter("id"))
        assertEquals("lib/al1-2", request.url.queryParameter("key"))
        assertEquals("5", request.url.queryParameter("count"))
        assertEquals(null, request.url.queryParameter("artist"))
        assertEquals(listOf("lib/al1-1", "lib/al1-2", "yt/dQw4w9WgXcQ"), Json.parseToJsonElement(request.body!!.utf8()).jsonObject["exclude"]!!.jsonArray.map { it.jsonPrimitive.content })

        respond("""{"title":"Picks radio","songs":[],"more":false,"problem":"Nothing like that in your library"}""")
        val none = client.radio(session(), "song", artist = "Sia", title = "Rewrite")
        assertEquals("Nothing like that in your library", none.problem)
        assertFalse(none.more)
        val byName = server.takeRequest().url
        assertEquals(listOf("Sia", "Rewrite", null, null, "25"), listOf("artist", "title", "id", "key", "count").map(byName::queryParameter))
    }

    @Test
    fun `a mix is asked for by its sentence, refresh only when wanted`() = runTest {
        respond("""{"id":"mix-1","prompt":"rainy sunday, mellow jazz","title":"","songs":[],"running":true,"problem":null,"ai":"Ollama gemma4:26b"}""")
        assertTrue(client.mix(session(), "rainy sunday, mellow jazz").running)
        val first = server.takeRequest().url
        assertEquals("/connect-tonearm/api/mix", first.encodedPath)
        assertEquals("rainy sunday, mellow jazz", first.queryParameter("prompt"))
        assertEquals(null, first.queryParameter("refresh"))

        respond("""{"id":"mix-1","prompt":"rainy sunday, mellow jazz","title":"Rainy Sunday Jazz","subtitle":null,"songs":[{"id":"al4-1","title":"Slow Rain"}],"running":false,"problem":null,"ai":"Ollama gemma4:26b"}""")
        val done = client.mix(session(), "rainy sunday, mellow jazz", refresh = true)
        assertEquals("Rainy Sunday Jazz", done.title)
        assertEquals(listOf("Slow Rain"), done.songs.map { it.title })
        assertEquals("Ollama gemma4:26b", done.ai)
        assertEquals("true", server.takeRequest().url.queryParameter("refresh"))

        respond("""{"id":"mix-1","prompt":"x","title":"","songs":[],"running":false,"problem":"The AI couldn't answer: timed out","ai":"Ollama gemma4:26b"}""")
        assertEquals("The AI couldn't answer: timed out", client.mix(session(), "x").problem)
        server.takeRequest()
        respond("""{"error":"This Tonearm server has no AI set up"}""", 404)
        try {
            client.mix(session(), "x")
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `daily mixes are asked for with the time zone`() = runTest {
        respond("""{"day":"2026-10-09","running":false,"problem":null,"mixes":[{"id":"daily-2026-10-09-1","title":"Daily Mix 1",
            "subtitle":"The Oscillators, Signal Garden and more","songs":[{"id":"al1-1","title":"Low Hum"}]}]}""")
        val daily = client.dailyMixes(session(), "Europe/Vienna")
        assertEquals("2026-10-09", daily.day)
        assertEquals("Daily Mix 1", daily.mixes.single().title)
        assertEquals(null, daily.mixes.single().prompt)
        val request = server.takeRequest().url
        assertEquals("/connect-tonearm/api/dailymixes", request.encodedPath)
        assertEquals("Europe/Vienna", request.queryParameter("tz"))
    }

    @Test
    fun `resume gives the device that played last, or null`() = runTest {
        respond("""{"handoff":{"device":"desktop-7c1d","name":"Tonearm on brabb-pc","online":false,"playing":false,
            "playedAt":1760000000000,"seenAt":1760000005000,"state":"{\"id\":\"desktop-7c1d\",\"name\":\"Tonearm on brabb-pc\",\"kind\":\"desktop\"}"}}""")
        val handoff = client.resume(session(), "phone-1")!!
        assertEquals("desktop-7c1d", handoff.device)
        assertEquals(1_760_000_005_000, handoff.seenAt)
        assertEquals("Tonearm on brabb-pc", json.decodeFromString(DeviceState.serializer(), handoff.state).name)
        val request = server.takeRequest().url
        assertEquals("/connect-tonearm/api/resume", request.encodedPath)
        assertEquals("phone-1", request.queryParameter("device"))
        respond("""{"handoff":null}""")
        assertNull(client.resume(session(), "phone-1"))
    }

    @Test
    fun `listening between two times sends only what's given and reads albums and minutes`() = runTest {
        respond("""{"since":1759701600000,"to":1760306400000,"plays":312,"minutes":1043,
            "artists":[{"artist":"The Oscillators","plays":120,"skips":3,"lastPlayed":1759870000000}],
            "albums":[{"artist":"The Oscillators","album":"Hi-Res Tones","plays":60,"lastPlayed":1759870000000}],
            "songs":[],"recent":[{"artist":"The Oscillators","title":"Low Hum","at":1759870000000,"album":"Hi-Res Tones","source":"library"}]}""")
        val listening = client.listeningBetween(session(), from = 1_759_701_600_000, to = 1_760_306_400_000)
        assertEquals(1043, listening.minutes)
        assertEquals(1_760_306_400_000, listening.to)
        assertEquals(AlbumCount("The Oscillators", "Hi-Res Tones", 60, 1_759_870_000_000), listening.albums.single())
        assertEquals("Hi-Res Tones" to "library", listening.recent.single().let { it.album to it.source })
        val first = server.takeRequest().url
        assertEquals("/connect-tonearm/api/listening", first.encodedPath)
        assertEquals(listOf("1759701600000", "1760306400000", "10", "10", "0", "100", null, null), listOf("from", "to", "artists", "albums", "songs", "recent", "before", "days").map(first::queryParameter))

        // An older server's answer still reads.
        respond("""{"since":1,"plays":3,"artists":[],"recent":[{"artist":"Sia","title":"Rewrite","at":9}]}""")
        val old = client.listeningBetween(session(), from = 1, to = null, recent = 20, before = 50)
        assertEquals(Listening(since = 1, plays = 3, recent = listOf(RecentPlay("Sia", "Rewrite", 9))), old)
        val second = server.takeRequest().url
        assertEquals(listOf(null, "50", "20"), listOf("to", "before", "recent").map(second::queryParameter))
    }

    @Test
    fun `the server's hello says which of radio, mixes, daily mixes, resume and stats it has`() = runTest {
        respond("""{"server":"tonearm","protocol":2,"version":"1.7.0","user":"demo","radio":true,"mix":false,"dailyMixes":true,"resume":true,"stats":true}""")
        val info = client.findServer(session())!!
        assertEquals(listOf(true, false, true, true, true), listOf(info.radio, info.mix, info.dailyMixes, info.resume, info.stats))
        respond("""{"server":"tonearm","protocol":2,"version":"1.6.0","user":"demo"}""")
        val old = client.findServer(session())!!
        assertEquals(listOf(false, false, false, false, false), listOf(old.radio, old.mix, old.dailyMixes, old.resume, old.stats))
    }

    @Test
    fun `the server's hello says when lidarr's lists hold only your own requests`() = runTest {
        respond("""{"server":"tonearm","protocol":2,"version":"1.8.0","user":"guest","lidarr":true,"ownRequests":true}""")
        assertTrue(client.findServer(session())!!.ownRequests)
        respond("""{"server":"tonearm","protocol":2,"version":"1.7.0","user":"guest","lidarr":true}""")
        assertFalse(client.findServer(session())!!.ownRequests)
    }

    @Test
    fun `the server's hello says how long its youtube fallback waits and whether youtube now is there`() = runTest {
        respond("""{"server":"tonearm","protocol":2,"version":"1.8.0","user":"demo","lidarr":true,"youtubeFallback":20,"youtubeNow":true}""")
        val info = client.findServer(session())!!
        assertEquals(20, info.youtubeFallback)
        assertTrue(info.youtubeNow)
        // Off on the server: no minutes, but Lidarr's YouTube indexer still gets an album right away.
        respond("""{"server":"tonearm","protocol":2,"version":"1.8.0","user":"demo","lidarr":true,"youtubeNow":true}""")
        assertEquals(listOf(0, true), client.findServer(session())!!.let { listOf(it.youtubeFallback, it.youtubeNow) })
        respond("""{"server":"tonearm","protocol":2,"version":"1.7.0","user":"demo","lidarr":true}""")
        assertEquals(listOf(0, false), client.findServer(session())!!.let { listOf(it.youtubeFallback, it.youtubeNow) })
        respond("""{"server":"tonearm","protocol":2,"user":"demo","youtubeFallback":"soon"}""")
        assertEquals(0, client.findServer(session())!!.youtubeFallback)
    }

    @Test
    fun `youtube now posts the lidarr album id and reads what was grabbed, or the problem`() = runTest {
        respond("""{"grabbed":true,"title":"Portishead - Dummy (YouTube Music)"}""")
        assertEquals(YouTubeNow(grabbed = true, title = "Portishead - Dummy (YouTube Music)"), client.youtubeNow(session(), 42))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/connect-tonearm/api/youtubenow", request.url.encodedPath)
        assertEquals("42", request.url.queryParameter("albumId"))
        assertEquals("alice", request.url.queryParameter("u"))

        respond("""{"grabbed":false,"problem":"No YouTube Music match for “Dummy”"}""")
        assertEquals(YouTubeNow(problem = "No YouTube Music match for “Dummy”"), client.youtubeNow(session(), 42))
    }

    @Test
    fun `youtube now for an album that isn't yours gives the server's reason`() = runTest {
        respond("""{"error":"That album isn't one of your requests"}""", 403)
        try {
            client.youtubeNow(session(), 42)
            error("expected failure")
        } catch (e: IntegrationHttpException) {
            assertEquals(403, e.code)
            assertEquals("That album isn't one of your requests", e.reason)
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
