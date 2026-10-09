package io.github.deadeyebarb.tonearm.connect

/** What the phone takes over when the car starts: the queue another device (the desktop) played last. */
object Handoffs {
    /** The queue to play from [index] at [positionMs]; [pause] is the device to pause once the phone plays, if any. */
    data class Plan(val queue: List<ConnectSong>, val index: Int, val positionMs: Long, val repeat: String, val pause: String?)

    /**
     * Null: another music server, nothing movable, or the phone played after it. LOCAL songs are dropped; if the current
     * one was LOCAL, it starts at the next movable song at 0. [Plan.pause] = the device id when it's online and playing.
     */
    fun plan(handoff: Handoff, state: DeviceState, server: String, phonePlayedAt: Long, now: Long): Plan? {
        if (handoff.playedAt <= phonePlayedAt) return null
        // Song ids only mean the same song on the same music server; a device that doesn't say is taken to be on it.
        if (state.server != null && !state.server.trimEnd('/').equals(server.trimEnd('/'), ignoreCase = true)) return null
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

    /** Online: extrapolated to now; offline: only up to [seenAt] (a desktop that suspended mid-song). */
    fun position(playback: PlaybackState, online: Boolean, seenAt: Long, now: Long): Long =
        playback.positionAt(if (online) now else minOf(seenAt, now))
}
