package io.github.deadeyebarb.tonearm.ui.detail

import androidx.lifecycle.viewModelScope
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.ui.common.LoaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who may change a playlist, and removing songs by position the way the server does. */
class PlaylistEditsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    private fun song(id: String, duration: Int = 100) = Song(id = id, title = id, duration = duration)

    private fun playlist(vararg ids: String) = ids.map { song(it) }.let { Playlist(id = "pl", entry = it, songCount = it.size, duration = it.sumOf { s -> s.duration ?: 0 }) }

    @Test
    fun `only your own playlists can be changed`() {
        assertTrue(Playlist(id = "1", owner = "demo").editableBy("demo"))
        assertTrue(Playlist(id = "1", owner = "Demo").editableBy("demo"))
        assertFalse(Playlist(id = "1", owner = "guest").editableBy("demo"))
        // Without an owner or a known username there's nothing to tell them apart by.
        assertTrue(Playlist(id = "1").editableBy("demo"))
        assertTrue(Playlist(id = "1", owner = "guest").editableBy(null))
    }

    @Test
    fun `a playlist the server marks read-only can't be changed, even your own`() {
        val body = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true,"playlists":{"playlist":[
              {"id":"p1","name":"Mine","owner":"demo","public":false,"songCount":2,"duration":300},
              {"id":"p2","name":"Smart","owner":"demo","public":true,"songCount":9,"duration":900,"readonly":true}]}}}
        """.trimIndent()
        val (mine, smart) = SubsonicApi.parse(json, body).playlists!!.playlist
        assertNull(mine.readonly)
        assertTrue(mine.editableBy("demo"))
        assertFalse(smart.editableBy("demo"))
    }

    @Test
    fun `finds the right copy of a song that's in the playlist twice`() {
        val songs = playlist("a", "x", "b", "x").entry
        assertEquals(1, songs.indexOfCopy("x", 0))
        assertEquals(3, songs.indexOfCopy("x", 1))
        assertNull(songs.indexOfCopy("x", 2))
        assertNull(songs.indexOfCopy("gone", 0))
    }

    @Test
    fun `removing a song keeps the count and length in step`() {
        val after = playlist("a", "b", "c").withoutEntry(1)
        assertEquals(listOf("a", "c"), after.entry.map { it.id })
        assertEquals(2, after.songCount)
        assertEquals(200, after.duration)
        assertEquals(after, after.withoutEntry(7))
    }

    @Test
    fun `quick removals one after another take out the songs that were tapped`() {
        // The server removes by position in its current list; each removal works on the list as it is by then.
        var server = listOf("a", "b", "c", "d", "b")
        var shown = playlist(*server.toTypedArray())
        // Tapped on the list as drawn: c (row 2), then the second b (row 4), then a (row 0).
        val taps = listOf(2, 4, 0).map { row -> shown.entry[row].id to shown.entry.take(row + 1).count { it.id == shown.entry[row].id } - 1 }
        for ((id, copy) in taps) {
            val index = shown.entry.indexOfCopy(id, copy)!!
            shown = shown.withoutEntry(index)
            server = server.filterIndexed { i, _ -> i != index }
        }
        assertEquals(listOf("b", "d"), server)
        assertEquals(server, shown.entry.map { it.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a reload that comes while songs are being removed waits its turn`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // As the playlist page does it: removals and reloads share the playlist's lock.
            val lock = Mutex()
            val server = mutableListOf("a", "b", "c", "d", "e")
            val changed = MutableStateFlow(0)
            val vm = LoaderViewModel({ lock.withLock { delay(10); playlist(*server.toTypedArray()) } }, changed)
            advanceUntilIdle()
            // b, c and d tapped in a row; each removal reloads the page when it's done.
            for (id in listOf("b", "c", "d")) {
                launch(Dispatchers.Main) {
                    lock.withLock {
                        val index = vm.value!!.entry.indexOfCopy(id, 0)!!
                        vm.mutate { it.withoutEntry(index) }
                        delay(100)
                        server.removeAt(index)
                        changed.update { it + 1 }
                    }
                }
            }
            advanceUntilIdle()
            assertEquals(listOf("a", "e"), server)
            assertEquals(server, vm.value!!.entry.map { it.id })
            vm.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
