package io.github.deadeyebarb.tonearm.connect

import java.net.URI
import kotlin.math.abs

/** What the phone takes over when the car starts: the queue another device (the desktop) played last. */
object Handoffs {
    /** The queue to play from [index] at [positionMs]; [pause] is the device to pause once the phone plays, if any. */
    data class Plan(val queue: List<ConnectSong>, val index: Int, val positionMs: Long, val repeat: String, val pause: String?)

    /**
     * Null: clearly another music server ([otherServer]), nothing movable, or the phone played after it. LOCAL songs are
     * dropped; if the current one was LOCAL, it starts at the next movable song at 0. [Plan.pause] = the device id when
     * it's online and playing.
     */
    fun plan(handoff: Handoff, state: DeviceState, server: String, phonePlayedAt: Long, now: Long): Plan? {
        if (handoff.playedAt <= phonePlayedAt) return null
        if (otherServer(state.server, server)) return null
        val playback = state.playback ?: return null
        if (playback.index !in playback.queue.indices) return null
        val movable = playback.queue.withIndex().filter { it.value.source != ConnectSong.LOCAL }
        val start = movable.indexOfFirst { it.index >= playback.index }.takeIf { it >= 0 } ?: return null
        val current = movable[start].index == playback.index
        return Plan(
            queue = movable.map { it.value },
            index = start,
            positionMs = if (current) position(playback, handoff.online, handoff.seenAt, now) else 0,
            repeat = playback.repeat,
            pause = handoff.device.takeIf { handoff.online && handoff.playing },
        )
    }

    /**
     * Whether a device's music server [address] is clearly not [server]: the same host and port, so the same front door,
     * with another path behind it. Song ids only mean the same song on the same server, but the handoff comes from the
     * Tonearm server the phone reached at its own music server, which serves one Navidrome: another host, port or scheme
     * (a LAN address on the desktop, a domain on the phone) is that server under another address. A device that doesn't
     * say is taken to be on it.
     */
    fun otherServer(address: String?, server: String): Boolean {
        val a = address?.let(::parse) ?: return false
        val b = parse(server) ?: return false
        return a.host.equals(b.host, ignoreCase = true) && a.port == b.port && !a.path.equals(b.path, ignoreCase = true)
    }

    private class Address(val host: String, val port: Int, val path: String)

    private fun parse(address: String): Address? = runCatching {
        val uri = URI(address.trim())
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        Address(host, port, uri.path.orEmpty().trimEnd('/'))
    }.getOrNull()

    /** Online: extrapolated to now; offline: only up to [seenAt] (a desktop that suspended mid-song). */
    fun position(playback: PlaybackState, online: Boolean, seenAt: Long, now: Long): Long =
        playback.positionAt(if (online) now else minOf(seenAt, now))

    /**
     * Where the phone, at [positionMs] of the song it took over at [fromMs] from a device that played on, starts it when
     * it plays at [now]: where the device has got to by then, so nothing is heard twice. Null when the phone was moved
     * away from where it took over, or the device would have finished the song by now.
     */
    fun catchUp(playback: PlaybackState, fromMs: Long, positionMs: Long, now: Long): Long? {
        if (abs(positionMs - fromMs) > SAME_PLACE_MS) return null
        val there = position(playback, online = true, seenAt = now, now = now)
        if (playback.durationMs > 0 && there >= playback.durationMs - SAME_PLACE_MS) return null
        return there.takeIf { it > positionMs + SAME_PLACE_MS }
    }

    /** Places in a song less than this apart count as the same. */
    private const val SAME_PLACE_MS = 2_000L
}
