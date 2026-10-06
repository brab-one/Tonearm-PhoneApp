package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRoute
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.likes.LikesDocument
import io.github.deadeyebarb.tonearm.likes.LikesSync
import io.github.deadeyebarb.tonearm.likes.PendingLike
import io.github.deadeyebarb.tonearm.likes.PendingLikes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LikesSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer()
    /** The plugin's store: value and version. */
    private var stored: String? = null
    private var version = 0L
    private var putsBeforeConflict = 0
    private var supported = true

    private fun like(id: String, at: Long) = PendingLike(TrackRef("Song $id", "Artist", youtubeId = id), likedAt = at)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val op = request.url.queryParameter("op")
                if (!supported) return MockResponse(body = """{"error":"Unknown op"}""")
                return when (op) {
                    "get" -> MockResponse(body = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                        put("value", kotlinx.serialization.json.JsonPrimitive(stored))
                        put("version", kotlinx.serialization.json.JsonPrimitive(version))
                    }))
                    "put" -> {
                        val ifVersion = request.url.queryParameter("ifVersion")!!.toLong()
                        if (putsBeforeConflict > 0) {
                            // Another device saved first.
                            putsBeforeConflict--
                            stored = json.encodeToString(LikesDocument.serializer(), LikesDocument(listOf(like("other", 5))))
                            version++
                        }
                        if (ifVersion != version) return MockResponse(body = """{"ok":false,"conflict":true,"version":$version}""")
                        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
                        stored = body["fields"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content
                        version++
                        MockResponse(body = """{"ok":true,"conflict":false,"version":$version}""")
                    }
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun sync() = LikesSync(ConnectClient(IntegrationHttp(OkHttpClient()) { null }, json), json)
    private val route get() = ConnectRoute.Lidarr(LidarrConfig(url = server.url("/").toString().trimEnd('/'), keyEnc = ""), "k")

    @Test
    fun `likes from both devices are kept, unlikes win over older likes`() {
        val phone = LikesDocument(listOf(like("a", 10), like("b", 20)), removed = mapOf("c" to 30))
        val desktop = LikesDocument(listOf(like("c", 25), like("d", 40)), removed = mapOf("a" to 15))
        val merged = PendingLikes.merge(phone, desktop, now = 100)
        assertEquals(listOf("b", "d"), merged.likes.map { it.ref.youtubeId })
        // Liking again after an unlike counts.
        val again = PendingLikes.merge(merged, LikesDocument(listOf(like("a", 50))), now = 100)
        assertEquals(listOf("b", "d", "a"), again.likes.map { it.ref.youtubeId })
    }

    @Test
    fun `a like reaches the store and comes back on the other device`() = runTest {
        val desktop = PendingLikes(tmp.newFile("desktop.json").also { it.delete() }, json)
        desktop.add(TrackRef("Karma Police", "Radiohead", youtubeId = "kp"))
        assertTrue(sync().sync(route, desktop))
        val phone = PendingLikes(tmp.newFile("phone.json").also { it.delete() }, json)
        assertTrue(sync().sync(route, phone))
        assertEquals(listOf("kp"), phone.items.value.map { it.ref.youtubeId })
        // Unliked on the phone: gone on the desktop after its next sync.
        phone.remove(TrackRef("Karma Police", "Radiohead", youtubeId = "kp"))
        sync().sync(route, phone)
        sync().sync(route, desktop)
        assertTrue(desktop.items.value.isEmpty())
    }

    @Test
    fun `a write that lost the race merges again`() = runTest {
        val phone = PendingLikes(tmp.newFile("p.json").also { it.delete() }, json)
        phone.add(TrackRef("Mine", "Me", youtubeId = "mine"))
        putsBeforeConflict = 1
        assertTrue(sync().sync(route, phone))
        val final = json.decodeFromString(LikesDocument.serializer(), stored!!)
        assertEquals(setOf("mine", "other"), final.likes.map { it.ref.youtubeId }.toSet())
    }

    @Test
    fun `an old plugin means no sharing, and nothing breaks`() = runTest {
        supported = false
        val phone = PendingLikes(tmp.newFile("o.json").also { it.delete() }, json)
        phone.add(TrackRef("Mine", "Me", youtubeId = "mine"))
        assertFalse(sync().sync(route, phone))
        assertEquals(1, phone.items.value.size)
    }
}
