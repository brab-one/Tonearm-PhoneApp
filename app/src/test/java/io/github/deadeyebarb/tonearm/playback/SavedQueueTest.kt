package io.github.deadeyebarb.tonearm.playback

import io.github.deadeyebarb.tonearm.mixes.RadioSeed
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedQueueTest {
    // As in AppContainer.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `a queue saved before radio still loads`() {
        val old = """{"items":[{"serverId":"s1","song":{"id":"al1-2","title":"Middle C"},"context":"album/s1/al1"}],"index":0,"positionMs":1200,"shuffle":false,"repeatMode":0}"""
        val queue = json.decodeFromString(SavedQueue.serializer(), old)
        assertEquals("Middle C", queue.items.single().song.title)
        assertEquals(1200, queue.positionMs)
        assertNull(queue.radio)
    }

    @Test
    fun `the radio comes back`() {
        val seed = RadioSeed(RadioSeed.SONG, Song(id = "yt1", title = "Low Hum", artist = "The Oscillators"), youtube = true)
        val queue = SavedQueue(
            items = listOf(SavedItem("ytmusic", Song(id = "yt1", title = "Low Hum"))),
            radio = SavedRadio(seed, "Radio · Low Hum"),
        )
        val back = json.decodeFromString(SavedQueue.serializer(), json.encodeToString(SavedQueue.serializer(), queue))
        assertEquals(queue, back)
        val picks = SavedRadio(RadioSeed(RadioSeed.PICKS), "Picks radio")
        assertEquals(picks, json.decodeFromString(SavedRadio.serializer(), json.encodeToString(SavedRadio.serializer(), picks)))
    }
}
