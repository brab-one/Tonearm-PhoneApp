package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.weekly.WeeklyAlbum
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyPicksTest {
    private val main = Json.parseToJsonElement(
        """{"id":4,"name":"Brainarr","enableAutomaticAdd":true,"shouldMonitor":"none","shouldSearch":false,"tags":[1],
        "implementation":"Brainarr","fields":[{"name":"provider","value":"ollama"},{"name":"recommendationMode","value":"artists"},
        {"name":"maxRecommendations","value":20},{"name":"apiKey","type":"password","value":""}]}""",
    ).jsonObject

    @Test
    fun `the weekly list picks albums, downloads them and stays off Lidarr's schedule`() {
        val body = WeeklyPicks.weeklyListBody(main, 5)
        assertNull(body["id"])
        assertEquals(WeeklyPicks.LIST_NAME, body["name"]!!.jsonPrimitive.content)
        assertEquals("false", body["enableAutomaticAdd"]!!.jsonPrimitive.content)
        assertEquals("specificAlbum", body["shouldMonitor"]!!.jsonPrimitive.content)
        assertEquals("true", body["shouldSearch"]!!.jsonPrimitive.content)
        val fields = body["fields"]!!.jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"] }
        assertEquals("specificAlbums", fields["recommendationMode"]!!.jsonPrimitive.content)
        assertEquals("5", fields["maxRecommendations"]!!.jsonPrimitive.content)
        assertEquals("ollama", fields["provider"]!!.jsonPrimitive.content)
        assertEquals("[1]", body["tags"].toString())
    }

    @Test
    fun `masked API keys are noticed`() {
        assertFalse(WeeklyPicks.hasMaskedSecret(main))
        val masked = Json.parseToJsonElement("""{"fields":[{"name":"openAIApiKey","type":"password","value":"********"}]}""").jsonObject
        assertTrue(WeeklyPicks.hasMaskedSecret(masked as JsonObject))
    }

    @Test
    fun `state round-trips through the playlist comment`() {
        val state = WeeklyState(created = 1_760_000_000_000, by = "phone", status = WeeklyState.READY,
            albums = listOf(WeeklyAlbum(7, "OK Computer", "Radiohead", 3, newArtist = true)))
        val comment = WeeklyPicks.comment(state)
        assertTrue(comment.startsWith("Brainarr's picks for the week of"))
        assertEquals(state, WeeklyPicks.parse(Playlist("p1", comment = comment)))
        assertNull(WeeklyPicks.parse(Playlist("p2", comment = "Kept from Brainarr's weekly picks")))
        assertNull(WeeklyPicks.parse(Playlist("p3")))
    }
}
