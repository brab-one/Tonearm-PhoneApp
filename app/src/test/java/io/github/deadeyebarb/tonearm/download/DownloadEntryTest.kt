package io.github.deadeyebarb.tonearm.download

import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadEntryTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun `downloads made before say what they replace as nothing`() {
        val old = json.decodeFromString(DownloadMeta.serializer(), """{"serverId":"s1","song":{"id":"a","title":"Slow Rain"}}""")
        assertNull(old.replaces)
        val upgrade = DownloadMeta("s1", Song(id = "b", title = "Slow Rain", suffix = "flac"), replaces = "s1/a")
        assertEquals(upgrade, json.decodeFromString(DownloadMeta.serializer(), json.encodeToString(DownloadMeta.serializer(), upgrade)))
    }
}
