package io.github.deadeyebarb.tonearm.playback

import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UnsavedDislikesTest {
    @get:Rule val tmp = TemporaryFolder()
    // As in AppContainer.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val song = QueueSong("s1", Song("al1-2", title = "Middle C", artist = "The Oscillators"))
    private val file get() = File(tmp.root, "unsaved-dislikes.json")

    /** No signal, or the Tonearm server down. */
    private var down = true
    private var worthRetrying = true
    private val sent = mutableListOf<QueueSong>()
    /** When each try was made (virtual ms). */
    private val tries = mutableListOf<Long>()

    private fun TestScope.unsaved(scope: CoroutineScope = backgroundScope) = UnsavedDislikes(
        file, json, scope,
        send = {
            tries += testScheduler.currentTime
            if (down) throw IOException("no signal")
            sent += it
        },
        worthRetrying = { worthRetrying },
        io = StandardTestDispatcher(testScheduler),
    ).also { runCurrent() }

    @Test
    fun `a dislike that failed is tried again, longer apart each time, until it's saved`() = runTest {
        val unsaved = unsaved()
        unsaved.add(song)
        runCurrent()
        assertTrue(file.exists())
        // Left out meanwhile, the YouTube Music copy too.
        assertTrue(song.song in unsaved)
        assertTrue(Song("dQw4w9WgXcQ", title = "Middle C (Official Audio)", artist = "The Oscillators") in unsaved)
        advanceTimeBy(130_001)
        assertEquals(listOf(10_000L, 30_000L, 70_000L, 130_000L), tries)
        down = false
        advanceTimeBy(60_000)
        assertEquals(listOf(10_000L, 30_000L, 70_000L, 130_000L, 190_000L), tries)
        assertEquals(listOf(song), sent)
        assertFalse(song.song in unsaved)
        assertFalse(file.exists())
        // Nothing left to try.
        advanceTimeBy(600_000)
        assertEquals(5, tries.size)
    }

    @Test
    fun `back online they're sent at once`() = runTest {
        val unsaved = unsaved()
        unsaved.add(song)
        advanceTimeBy(3_000)
        down = false
        unsaved.retry()
        runCurrent()
        assertEquals(listOf(3_000L), tries)
        assertEquals(listOf(song), sent)
    }

    @Test
    fun `they outlast the service and are sent at the next start`() = runTest {
        val service = CoroutineScope(backgroundScope.coroutineContext + Job())
        unsaved(service).add(song)
        runCurrent()
        service.cancel()
        down = false
        val next = unsaved()
        assertEquals(listOf(song), sent)
        assertEquals(listOf(0L), tries)
        assertFalse(song.song in next)
        assertFalse(file.exists())
    }

    @Test
    fun `once the server is known to keep no dislikes they're dropped`() = runTest {
        val unsaved = unsaved()
        unsaved.add(song)
        worthRetrying = false
        advanceTimeBy(10_001)
        assertEquals(listOf(10_000L), tries)
        assertFalse(song.song in unsaved)
        assertFalse(file.exists())
        advanceTimeBy(600_000)
        assertEquals(1, tries.size)
    }
}
