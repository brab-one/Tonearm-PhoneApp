package io.github.deadeyebarb.tonearm.widget

import io.github.deadeyebarb.tonearm.playback.PlaybackCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutsTest {
    @Test
    fun `each shortcut starts its radio`() {
        assertEquals(PlaybackCommands.SEED_PICKS, Shortcuts.seedFor(Shortcuts.ACTION_PLAY_PICKS))
        assertEquals(PlaybackCommands.SEED_CURRENT, Shortcuts.seedFor(Shortcuts.ACTION_PLAY_RADIO))
        assertEquals(Shortcuts.PICKS, Shortcuts.idFor(PlaybackCommands.SEED_PICKS))
        assertEquals(Shortcuts.RADIO, Shortcuts.idFor(PlaybackCommands.SEED_CURRENT))
    }

    @Test
    fun `other actions start nothing`() {
        assertNull(Shortcuts.seedFor(null))
        assertNull(Shortcuts.seedFor("android.intent.action.MAIN"))
    }
}
