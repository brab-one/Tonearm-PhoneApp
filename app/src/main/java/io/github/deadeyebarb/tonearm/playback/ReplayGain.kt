package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.Player
import io.github.deadeyebarb.tonearm.data.ReplayGainMode
import io.github.deadeyebarb.tonearm.subsonic.ReplayGainInfo
import kotlin.math.min
import kotlin.math.pow

object ReplayGain {
    /**
     * Linear volume for a track. Only attenuates (player volume can't exceed 1), and never lets
     * the peak clip. Tracks without ReplayGain tags play unchanged.
     */
    fun linearGain(mode: ReplayGainMode, preampDb: Float, info: ReplayGainInfo?): Float {
        if (mode == ReplayGainMode.OFF || info == null) return 1f
        val (gain, peak) = when (mode) {
            ReplayGainMode.TRACK -> (info.trackGain ?: info.albumGain) to (info.trackPeak ?: info.albumPeak)
            ReplayGainMode.ALBUM -> (info.albumGain ?: info.trackGain) to (info.albumPeak ?: info.trackPeak)
            ReplayGainMode.OFF -> null to null
        }
        val db = (gain ?: info.fallbackGain ?: return 1f) + preampDb
        var linear = 10f.pow(db / 20f)
        if (peak != null && peak > 0f) linear = min(linear, 1f / peak)
        return linear.coerceIn(0f, 1f)
    }
}

/** Combines ReplayGain and the sleep timer's fade-out into the player's volume. Main thread only. */
class VolumeMixer {
    private var player: Player? = null

    var replayGain = 1f
        set(value) {
            field = value
            apply()
        }

    var fade = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            apply()
        }

    fun attach(player: Player) {
        this.player = player
        apply()
    }

    fun detach() {
        player = null
    }

    private fun apply() {
        player?.volume = replayGain * fade
    }
}
