package io.github.deadeyebarb.tonearm.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffsTest {
    private val server = "https://music.example"
    private val now = 1_760_000_100_000L

    private fun song(id: String, source: String = ConnectSong.SERVER) = ConnectSong(id, source, title = id)

    private fun state(
        queue: List<ConnectSong> = listOf(song("s1"), song("s2"), song("s3")),
        index: Int = 1,
        playing: Boolean = true,
        server: String? = this.server,
    ) = DeviceState(
        "desktop-1", "Desk", "desktop", server,
        PlaybackState(playing = playing, positionMs = 20_000, durationMs = 300_000, at = now - 60_000, index = index, queue = queue, repeat = "all"),
    )

    private fun handoff(online: Boolean = false, playing: Boolean = false, playedAt: Long = now - 30_000, seenAt: Long = now - 50_000) =
        Handoff("desktop-1", "Desk", online, playing, playedAt, seenAt, state = "")

    @Test
    fun `takes the desktop's queue, song and repeat`() {
        val plan = Handoffs.plan(handoff(), state(), "$server/", phonePlayedAt = now - 3_600_000, now = now)!!
        assertEquals(listOf("s1", "s2", "s3"), plan.queue.map { it.id })
        assertEquals(1, plan.index)
        assertEquals("all", plan.repeat)
        // Offline: it played until it was last seen, 10 s after the position was read.
        assertEquals(30_000, plan.positionMs)
        assertNull(plan.pause)
    }

    @Test
    fun `the same server under another address counts, one that doesn't say too`() {
        // The handoff came from the Tonearm server beside the phone's own music server: a LAN address on the desktop is that.
        assertEquals(1, Handoffs.plan(handoff(), state(server = "http://192.168.1.11:4533"), server, 0, now)!!.index)
        assertEquals(1, Handoffs.plan(handoff(), state(server = "http://127.0.0.1:8444"), "http://10.0.2.2:8444/", 0, now)!!.index)
        assertEquals(1, Handoffs.plan(handoff(), state(server = "http://music.example"), server, 0, now)!!.index)
        assertEquals(1, Handoffs.plan(handoff(), state(server = null), server, 0, now)!!.index)
        assertEquals(1, Handoffs.plan(handoff(), state(server = "HTTPS://music.example/"), server, 0, now)!!.index)
        assertEquals(1, Handoffs.plan(handoff(), state(server = "not an address"), server, 0, now)!!.index)
    }

    @Test
    fun `another path behind the same host and port is clearly another server`() {
        assertNull(Handoffs.plan(handoff(), state(server = "https://music.example/other"), server, 0, now))
        assertNull(Handoffs.plan(handoff(), state(server = "https://music.example:443/navidrome"), "https://music.example/music/", 0, now))
        assertEquals(1, Handoffs.plan(handoff(), state(server = "https://music.example/Music"), "https://music.example/music/", 0, now)!!.index)
    }

    @Test
    fun `the phone played after it, so the phone's queue stays`() {
        assertNull(Handoffs.plan(handoff(playedAt = now - 30_000), state(), server, phonePlayedAt = now - 10_000, now = now))
        assertNull(Handoffs.plan(handoff(playedAt = now - 30_000), state(), server, phonePlayedAt = now - 30_000, now = now))
    }

    @Test
    fun `local songs are left out, and a local current song starts the next movable one at 0`() {
        val queue = listOf(song("s1"), song("/home/me/a.flac", ConnectSong.LOCAL), song("y1", ConnectSong.YOUTUBE), song("s3"))
        val plan = Handoffs.plan(handoff(), state(queue, index = 1), server, 0, now)!!
        assertEquals(listOf("s1", "y1", "s3"), plan.queue.map { it.id })
        assertEquals(1, plan.index)
        assertEquals(0, plan.positionMs)
        // The current song is movable: its place among the movable ones.
        assertEquals(2, Handoffs.plan(handoff(), state(queue, index = 3), server, 0, now)!!.index)
    }

    @Test
    fun `nothing movable from the current song on gives nothing`() {
        val queue = listOf(song("s1"), song("/a.flac", ConnectSong.LOCAL), song("/b.flac", ConnectSong.LOCAL))
        assertNull(Handoffs.plan(handoff(), state(queue, index = 1), server, 0, now))
        assertNull(Handoffs.plan(handoff(), state(emptyList(), index = -1), server, 0, now))
        assertNull(Handoffs.plan(handoff(), state().copy(playback = null), server, 0, now))
    }

    @Test
    fun `the desktop is paused only when it's online and playing`() {
        assertEquals("desktop-1", Handoffs.plan(handoff(online = true, playing = true), state(), server, 0, now)!!.pause)
        assertNull(Handoffs.plan(handoff(online = true, playing = false), state(playing = false), server, 0, now)!!.pause)
        assertNull(Handoffs.plan(handoff(online = false, playing = true), state(), server, 0, now)!!.pause)
    }

    @Test
    fun `the position moves on only online and playing, and offline only up to when it was last seen`() {
        val playing = state().playback!!
        assertEquals(80_000, Handoffs.position(playing, online = true, seenAt = now - 50_000, now = now))
        assertEquals(30_000, Handoffs.position(playing, online = false, seenAt = now - 50_000, now = now))
        // Seen before the position was read: never earlier than the position itself.
        assertEquals(20_000, Handoffs.position(playing, online = false, seenAt = now - 90_000, now = now))
        val paused = playing.copy(playing = false)
        assertEquals(20_000, Handoffs.position(paused, online = true, seenAt = now, now = now))
        assertEquals(20_000, Handoffs.position(paused, online = false, seenAt = now, now = now))
    }

    @Test
    fun `the phone starts where a desktop that played on has got to by then`() {
        // Taken over at 80 s (online and playing); the phone plays 18 s later.
        val playing = state().playback!!
        val later = now + 18_000
        assertEquals(98_000L, Handoffs.catchUp(playing, fromMs = 80_000, positionMs = 80_000, now = later))
        // Moved meanwhile (a seek in the car), or hardly any later: it stays.
        assertNull(Handoffs.catchUp(playing, fromMs = 80_000, positionMs = 10_000, now = later))
        assertNull(Handoffs.catchUp(playing, fromMs = 80_000, positionMs = 80_000, now = now + 1_000))
        // Long after, the desktop would have finished the song: no jump to its end.
        assertNull(Handoffs.catchUp(playing, fromMs = 80_000, positionMs = 80_000, now = now + 3_600_000))
        // A paused desktop didn't move on.
        assertNull(Handoffs.catchUp(playing.copy(playing = false), fromMs = 20_000, positionMs = 20_000, now = later))
    }
}
