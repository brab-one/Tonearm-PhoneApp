package io.github.deadeyebarb.tonearm.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.deadeyebarb.tonearm.MainActivity
import io.github.deadeyebarb.tonearm.R
import io.github.deadeyebarb.tonearm.playback.PlaybackCommands

/**
 * The launcher icon's shortcuts: Picks (a radio from the weekly picks and the songs you like) and Radio (from the song
 * playing or last played). Published from the app, since a shortcuts.xml can't name the debug build's package.
 */
object Shortcuts {
    const val ACTION_PLAY_PICKS = "io.github.deadeyebarb.tonearm.action.PLAY_PICKS"
    const val ACTION_PLAY_RADIO = "io.github.deadeyebarb.tonearm.action.PLAY_RADIO"
    const val PICKS = "picks"
    const val RADIO = "radio"

    /** What radio a shortcut's [action] starts ([PlaybackCommands.SEED_PICKS] or [PlaybackCommands.SEED_CURRENT]); null for other actions. */
    fun seedFor(action: String?): String? = when (action) {
        ACTION_PLAY_PICKS -> PlaybackCommands.SEED_PICKS
        ACTION_PLAY_RADIO -> PlaybackCommands.SEED_CURRENT
        else -> null
    }

    /** The shortcut id that started [seed], for the launcher's ranking. */
    fun idFor(seed: String): String = if (seed == PlaybackCommands.SEED_PICKS) PICKS else RADIO

    fun publish(context: Context) {
        val shortcuts = listOf(
            shortcut(context, PICKS, R.string.shortcut_picks, R.string.shortcut_picks_long, R.drawable.ic_shortcut_picks, ACTION_PLAY_PICKS),
            shortcut(context, RADIO, R.string.shortcut_radio, R.string.shortcut_radio_long, R.drawable.ic_shortcut_radio, ACTION_PLAY_RADIO),
        )
        // Setting them again counts against the launcher's rate limit; only when they changed.
        val current = ShortcutManagerCompat.getDynamicShortcuts(context)
        if (current.map { it.id to it.shortLabel.toString() } != shortcuts.map { it.id to it.shortLabel.toString() }) {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    private fun shortcut(context: Context, id: String, label: Int, longLabel: Int, icon: Int, action: String) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setLongLabel(context.getString(longLabel))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, MainActivity::class.java).setAction(action))
            .build()
}
