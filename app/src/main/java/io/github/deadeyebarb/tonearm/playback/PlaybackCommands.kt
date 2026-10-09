package io.github.deadeyebarb.tonearm.playback

/** Custom session commands for radio and "Not this one": the car's buttons, the app, the shortcuts and the widget send them. */
object PlaybackCommands {
    /** Skips the song, takes it out of the queue and dislikes it. */
    const val NOT_THIS_ONE = "io.github.deadeyebarb.tonearm.NOT_THIS_ONE"
    /** The car button: a radio from the current song, or off when one plays. */
    const val TOGGLE_RADIO = "io.github.deadeyebarb.tonearm.TOGGLE_RADIO"
    /** Args: [ARG_SEED]. */
    const val START_RADIO = "io.github.deadeyebarb.tonearm.START_RADIO"
    const val STOP_RADIO = "io.github.deadeyebarb.tonearm.STOP_RADIO"

    /** What [START_RADIO] starts from: [SEED_CURRENT] (the current song) or [SEED_PICKS]. */
    const val ARG_SEED = "seed"
    const val SEED_CURRENT = "current"
    const val SEED_PICKS = "picks"

    /** Session extra: the radio's title while a radio plays; absent otherwise. */
    const val EXTRA_RADIO = "tonearm.radio"
}
