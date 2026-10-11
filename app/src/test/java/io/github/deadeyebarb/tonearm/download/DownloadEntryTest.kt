package io.github.deadeyebarb.tonearm.download

import androidx.media3.exoplayer.offline.Download
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadEntryTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val song = QueueSong("s1", Song(id = "a", title = "Slow Rain"))

    private fun entry(state: Int, stopReason: Int = Download.STOP_REASON_NONE) = DownloadEntry("s1/a", song, state, 0f, 0, stopReason)

    @Test
    fun `only downloads keep offline stopped are held`() {
        assertTrue(entry(Download.STATE_STOPPED, DownloadRepository.STOP_REASON_KEEP_OFFLINE).held)
        assertFalse(entry(Download.STATE_STOPPED, 1).held)
        assertFalse(entry(Download.STATE_QUEUED).held)
        assertFalse(entry(Download.STATE_STOPPED, DownloadRepository.STOP_REASON_KEEP_OFFLINE).active)
    }

    @Test
    fun `downloads made before say what they replace as nothing`() {
        val old = json.decodeFromString(DownloadMeta.serializer(), """{"serverId":"s1","song":{"id":"a","title":"Slow Rain"}}""")
        assertNull(old.replaces)
        val upgrade = DownloadMeta("s1", Song(id = "b", title = "Slow Rain", suffix = "flac"), replaces = "s1/a")
        assertEquals(upgrade, json.decodeFromString(DownloadMeta.serializer(), json.encodeToString(DownloadMeta.serializer(), upgrade)))
    }

    @Test
    fun `a download finished short of the song's size before sizes were checked was cut off`() {
        // The proxy sent no length, and the connection dropped at 475 136 of 685 000 bytes.
        assertTrue(DownloadRepository.cutOff(475_136, 685_000, checked = false))
        assertFalse(DownloadRepository.cutOff(685_000, 685_000, checked = false))
        // Without a size there's nothing to tell by.
        assertFalse(DownloadRepository.cutOff(475_136, null, checked = false))
        assertFalse(DownloadRepository.cutOff(475_136, 0, checked = false))
        // Checked as it came in: what finished is the whole file the server sent, even when it changed since.
        assertFalse(DownloadRepository.cutOff(475_136, 685_000, checked = true))
    }
}
