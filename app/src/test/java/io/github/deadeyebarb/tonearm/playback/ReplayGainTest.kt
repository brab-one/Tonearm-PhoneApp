package io.github.deadeyebarb.tonearm.playback

import io.github.deadeyebarb.tonearm.data.ReplayGainMode
import io.github.deadeyebarb.tonearm.subsonic.ReplayGainInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainTest {
    private val info = ReplayGainInfo(trackGain = -6f, albumGain = -8f, trackPeak = 0.5f, albumPeak = 0.9f)

    @Test
    fun `off and untagged tracks are unchanged`() {
        assertEquals(1f, ReplayGain.linearGain(ReplayGainMode.OFF, 0f, info), 0f)
        assertEquals(1f, ReplayGain.linearGain(ReplayGainMode.TRACK, 0f, null), 0f)
        assertEquals(1f, ReplayGain.linearGain(ReplayGainMode.TRACK, 0f, ReplayGainInfo()), 0f)
    }

    @Test
    fun `track and album modes pick their gain`() {
        assertEquals(0.501f, ReplayGain.linearGain(ReplayGainMode.TRACK, 0f, info), 0.001f)
        assertEquals(0.398f, ReplayGain.linearGain(ReplayGainMode.ALBUM, 0f, info), 0.001f)
    }

    @Test
    fun `never amplifies and never clips`() {
        // +6 dB pre-amp on a -6 dB track would be unity, but the 0.5 peak allows up to 2x: capped at 1.
        assertEquals(1f, ReplayGain.linearGain(ReplayGainMode.TRACK, 12f, info), 0f)
        // A loud peak limits the result below what the gain asks for.
        val hot = ReplayGainInfo(trackGain = -1f, trackPeak = 1.25f)
        assertEquals(0.8f, ReplayGain.linearGain(ReplayGainMode.TRACK, 0f, hot), 0.001f)
    }

    @Test
    fun `album mode falls back to track gain`() {
        val trackOnly = ReplayGainInfo(trackGain = -6f)
        assertEquals(0.501f, ReplayGain.linearGain(ReplayGainMode.ALBUM, 0f, trackOnly), 0.001f)
    }
}
