package io.github.deadeyebarb.tonearm.widget

import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.playback.SavedQueue

/** What the widget and the quick settings tile show: the song playing or paused, or the saved one after a restart. */
data class NowPlayingCard(
    val serverId: String?,
    val coverArt: String?,
    val title: String,
    val artist: String,
    /**
     * Shows Pause: play wanted and something to play, as the player's own button (so buffering doesn't flicker the
     * icon, and a queue that ended or a song that failed shows Play).
     */
    val playing: Boolean,
    val index: Int,
    val count: Int,
    /** The playback service runs: the controls act at once. */
    val live: Boolean,
) {
    companion object {
        fun from(entry: QueueSong?, playing: Boolean, index: Int, count: Int): NowPlayingCard? = entry?.let {
            NowPlayingCard(it.serverId, it.song.coverArt, it.song.title, it.song.artistLabel, playing, index, count, live = true)
        }

        /** The saved queue's song, paused; null when there's none. */
        fun fromSaved(saved: SavedQueue): NowPlayingCard? = saved.items.getOrNull(saved.index)?.let {
            NowPlayingCard(it.serverId, it.song.coverArt, it.song.title, it.song.artistLabel, playing = false, saved.index, saved.items.size, live = false)
        }
    }
}
