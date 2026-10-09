package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.AlbumCount
import io.github.deadeyebarb.tonearm.connect.RecentPlay
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ListeningStatsTest {
    private val vienna = ZoneId.of("Europe/Vienna")
    private val germany = Locale.forLanguageTag("de-DE")
    private val us = Locale.forLanguageTag("en-US")
    private fun at(text: String, zone: ZoneId = vienna) = ZonedDateTime.of(LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    /** Wednesday 7 October 2026, in the afternoon. */
    private val wednesday = at("2026-10-07T15:30")

    @Test
    fun `this week starts on the locale's first day`() {
        val monday = ListeningStats.window(Period.WEEK, 0, wednesday, vienna, germany)
        assertEquals(at("2026-10-05T00:00"), monday.from)
        assertEquals(at("2026-10-12T00:00"), monday.to)
        assertEquals("THIS WEEK · 5–11 OCT", monday.label)

        val sunday = ListeningStats.window(Period.WEEK, 0, wednesday, vienna, us)
        assertEquals(at("2026-10-04T00:00"), sunday.from)
        assertEquals(at("2026-10-11T00:00"), sunday.to)
        assertEquals("THIS WEEK · 4–10 OCT", sunday.label)
    }

    @Test
    fun `stepping back a week, and further`() {
        val last = ListeningStats.window(Period.WEEK, -1, wednesday, vienna, germany)
        assertEquals(at("2026-09-28T00:00"), last.from)
        assertEquals(at("2026-10-05T00:00"), last.to)
        assertEquals("LAST WEEK · 28 SEP–4 OCT", last.label)
        assertEquals("21–27 SEP", ListeningStats.window(Period.WEEK, -2, wednesday, vienna, germany).label)
        assertEquals("29 DEC 2025–4 JAN 2026", ListeningStats.window(Period.WEEK, -40, wednesday, vienna, germany).label)
    }

    @Test
    fun `months, across the new year`() {
        val january = at("2027-01-20T10:00")
        val now = ListeningStats.window(Period.MONTH, 0, january, vienna, germany)
        assertEquals(at("2027-01-01T00:00"), now.from)
        assertEquals(at("2027-02-01T00:00"), now.to)
        assertEquals("THIS MONTH · JANUARY", now.label)
        val december = ListeningStats.window(Period.MONTH, -1, january, vienna, germany)
        assertEquals(at("2026-12-01T00:00"), december.from)
        assertEquals(at("2027-01-01T00:00"), december.to)
        assertEquals("LAST MONTH · DECEMBER 2026", december.label)
        assertEquals("NOVEMBER 2026", ListeningStats.window(Period.MONTH, -2, january, vienna, germany).label)
    }

    @Test
    fun `the week the clocks go back is an hour longer`() {
        val week = ListeningStats.window(Period.WEEK, 0, at("2026-10-22T12:00"), vienna, germany)
        assertEquals(169 * 3_600_000L, week.to - week.from)
        val spring = ListeningStats.window(Period.WEEK, 0, at("2026-03-25T12:00"), vienna, germany)
        assertEquals(167 * 3_600_000L, spring.to - spring.from)
    }

    @Test
    fun `plays are grouped by day, at midnight where you are`() {
        fun play(title: String, at: String) = RecentPlay("The Oscillators", title, at(at))
        val plays = listOf(
            play("a", "2026-10-07T00:00"),
            play("b", "2026-10-06T23:59"),
            play("c", "2026-10-06T00:01"),
            play("d", "2026-10-05T23:30"),
            play("e", "2025-12-31T22:00"),
        )
        val days = ListeningStats.dayGroups(plays, wednesday, vienna)
        assertEquals(listOf("TODAY", "YESTERDAY", "MON 5 OCT", "WED 31 DEC 2025"), days.map { it.label })
        assertEquals(listOf(listOf("a"), listOf("b", "c"), listOf("d"), listOf("e")), days.map { day -> day.plays.map { it.title } })
        // The same plays seen from London: 00:00 in Vienna is still the day before there.
        val london = ListeningStats.dayGroups(plays.take(2), wednesday, ZoneId.of("Europe/London"))
        assertEquals(listOf("YESTERDAY"), london.map { it.label })
    }

    // A fake Navidrome for the top albums: its search finds album titles, as many as asked for.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()

    private fun album(id: String, name: String, artist: String, artistId: String = "", songs: List<String> = listOf(artist)) = buildJsonObject {
        put("id", id); put("name", name); put("artist", artist); put("artistId", artistId)
        put("song", JsonArray(songs.mapIndexed { i, by -> buildJsonObject { put("id", "$id-$i"); put("title", "Song $i"); put("artist", by) } }))
    }

    private val albums = listOf("ABBA", "Bee Gees", "Blondie", "Cher", "Eagles", "Fleetwood Mac").mapIndexed { i, artist -> album("gh$i", "Greatest Hits", artist) } + listOf(
        album("q1", "Greatest Hits", "Queen", "ar-queen"),
        album("q2", "A Night at the Opera", "Queen", "ar-queen"),
        album("qb", "Under Pressure", "Queen & David Bowie"),
        album("va2", "Now That's Ambient", "Various Artists", songs = listOf("Breakbeat Unit", "Vector Choir")),
        album("va1", "Now That's Ambient", "Various Artists", songs = listOf("Breakbeat Unit", "Signal Garden feat. Someone")),
    )

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
    private fun brief(album: JsonObject) = JsonObject(album - "song")
    private fun subsonic(body: String) = MockResponse(body = """{"subsonic-response":{"status":"ok","version":"1.16.1",$body}}""")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = { name: String -> request.url.queryParameter(name).orEmpty() }
                return when (request.url.encodedPath.removePrefix("/rest/").removeSuffix(".view")) {
                    "search3" -> subsonic(""""searchResult3":{"album":${JsonArray(albums.filter { q("query").lowercase() in it.text("name").lowercase() }.take(q("albumCount").toInt()).map(::brief))}}""")
                    "getArtist" -> subsonic(""""artist":{"id":"${q("id")}","name":"Queen","album":${JsonArray(albums.filter { it.text("artistId") == q("id") }.map(::brief))}}""")
                    "getAlbum" -> albums.firstOrNull { it.text("id") == q("id") }?.let { subsonic(""""album":$it""") } ?: MockResponse(code = 404)
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private val api by lazy {
        val session = ServerSession(ServerConfig(name = "Music", baseUrl = server.url("/").toString(), username = "alice"), "secret", OkHttpClient())
        SubsonicApi({ session }, json)
    }
    private val artists = mapOf("queen" to Artist("ar-queen", "Queen"))
    private suspend fun libraryAlbum(artist: String, album: String, artists: Map<String, Artist> = this.artists) =
        ListeningStats.libraryAlbum(api, AlbumCount(artist, album, plays = 9)) { artists }?.id

    @Test
    fun `a common title is found past other artists' albums of that name`() = runTest {
        assertEquals("q1", libraryAlbum("Queen", "Greatest Hits", artists = emptyMap()))
    }

    @Test
    fun `the first artist named is enough`() = runTest {
        assertEquals("qb", libraryAlbum("Queen", "Under Pressure"))
        assertEquals("qb", libraryAlbum("Queen feat. David Bowie", "Under Pressure"))
    }

    @Test
    fun `a title the search doesn't find is looked for among the artist's albums`() = runTest {
        assertEquals("q2", libraryAlbum("Queen", "A Night at the Opera (2011 Remaster)"))
    }

    @Test
    fun `a compilation is the album of that title with a song by them`() = runTest {
        assertEquals("va1", libraryAlbum("Signal Garden", "Now That's Ambient"))
    }

    @Test
    fun `another artist's album of that title isn't theirs`() = runTest {
        assertNull(libraryAlbum("Midnight Quartet", "Greatest Hits"))
        assertNull(libraryAlbum("Queen", "Jazz"))
    }
}
