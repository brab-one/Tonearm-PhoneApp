package io.github.deadeyebarb.tonearm.download

import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflinePlannerTest {
    private val mb = 1024L * 1024

    private fun song(id: String, size: Long? = 10 * mb, artist: String = "The Oscillators", serverId: String = "s1") =
        QueueSong(serverId, Song(id = id, title = "Song $id", artist = artist, size = size))

    private fun id(id: String) = "s1/$id"

    private fun plan(
        sources: List<List<QueueSong>?>,
        downloads: Set<String> = emptySet(),
        failed: Set<String> = emptySet(),
        state: OfflineState = OfflineState(),
        playing: Set<String> = emptySet(),
        budget: Long = 1024 * mb,
        disliked: Set<String> = emptySet(),
    ) = OfflinePlanner.plan(sources, "s1", downloads, failed, state, playing, budget) { it.id in disliked }

    @Test
    fun `weekly picks come before likes, and each song is kept once`() {
        val picks = listOf(song("p1"), song("p2"))
        val liked = listOf(song("l1"), song("p2"), song("l2"))
        val plan = plan(listOf(picks, liked))
        assertEquals(listOf("p1", "p2", "l1", "l2"), plan.add.map { it.song.id })
        assertEquals(listOf(id("p1"), id("p2"), id("l1"), id("l2")), plan.wanted.toList())
    }

    @Test
    fun `the space fills in order, then the rest is left out`() {
        val picks = listOf(song("p1", 40 * mb), song("p2", 40 * mb))
        val liked = listOf(song("l1", 30 * mb), song("l2", 5 * mb))
        val plan = plan(listOf(picks, liked), budget = 100 * mb)
        // l2 would fit, but likes go newest first: once one doesn't fit, the older ones don't either.
        assertEquals(listOf("p1", "p2"), plan.add.map { it.song.id })
        assertEquals(2, plan.leftOut)
    }

    @Test
    fun `the size is the file's, else its length at its bit rate, else a guess`() {
        assertEquals(12_345L, OfflinePlanner.sizeOf(Song(id = "a", size = 12_345, duration = 100, bitRate = 1000)))
        assertEquals(200L * 1000 * 125, OfflinePlanner.sizeOf(Song(id = "b", duration = 200, bitRate = 1000)))
        assertEquals(OfflinePlanner.UNKNOWN_SIZE, OfflinePlanner.sizeOf(Song(id = "c", duration = 200)))
        assertEquals(OfflinePlanner.UNKNOWN_SIZE, OfflinePlanner.sizeOf(Song(id = "d")))
    }

    @Test
    fun `YouTube Music songs, phone files, disliked and skipped songs are left out`() {
        val liked = listOf(
            song("yt1", serverId = YouTubeMusic.SOURCE_ID), song("f1", serverId = LocalMusic.SOURCE_ID),
            song("d1"), song("s1"), song("ok"),
        )
        val plan = plan(listOf(emptyList(), liked), state = OfflineState(skipped = setOf(id("s1"))), disliked = setOf("d1"))
        assertEquals(listOf("ok"), plan.add.map { it.song.id })
        // Still in the likes: still skipped.
        assertEquals(setOf(id("s1")), plan.skipped)
    }

    @Test
    fun `the user's own downloads are neither touched nor counted`() {
        val picks = listOf(song("own", 90 * mb), song("p1", 90 * mb))
        val plan = plan(listOf(picks, emptyList()), downloads = setOf(id("own"), id("other")), budget = 100 * mb)
        assertEquals(listOf("p1"), plan.add.map { it.song.id })
        assertEquals(setOf(id("p1")), plan.wanted)
        assertEquals(emptyList<String>(), plan.remove)
    }

    @Test
    fun `kept songs nobody wants anymore go, except the one playing`() {
        val state = OfflineState(kept = setOf(id("p1"), id("old"), id("playing"), "s2/elsewhere"))
        val downloads = setOf(id("p1"), id("old"), id("playing"), "s2/elsewhere")
        val plan = plan(listOf(listOf(song("p1")), emptyList()), downloads = downloads, state = state, playing = setOf(id("playing")))
        // Already there: nothing to add.
        assertEquals(emptyList<QueueSong>(), plan.add)
        // Another server's are left alone.
        assertEquals(listOf(id("old")), plan.remove)
    }

    @Test
    fun `a list that couldn't be read keeps what's kept`() {
        val state = OfflineState(kept = setOf(id("p1"), id("l1")), skipped = setOf(id("gone")))
        val plan = plan(listOf(null, listOf(song("l2"))), downloads = setOf(id("p1"), id("l1")), state = state)
        assertEquals(emptyList<String>(), plan.remove)
        assertEquals(listOf("l2"), plan.add.map { it.song.id })
        assertEquals(setOf(id("gone")), plan.skipped)
    }

    @Test
    fun `failed downloads are tried again and skipped songs off the lists are forgotten`() {
        val state = OfflineState(kept = setOf(id("p1")), skipped = setOf(id("gone")))
        val plan = plan(listOf(listOf(song("p1")), emptyList()), downloads = setOf(id("p1")), failed = setOf(id("p1")), state = state)
        assertEquals(listOf("p1"), plan.add.map { it.song.id })
        assertEquals(emptySet<String>(), plan.skipped)
    }
}
