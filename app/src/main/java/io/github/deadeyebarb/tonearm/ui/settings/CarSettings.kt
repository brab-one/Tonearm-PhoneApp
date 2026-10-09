package io.github.deadeyebarb.tonearm.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import io.github.deadeyebarb.tonearm.data.AppSettings

/** Settings → Car: what happens when the car starts. */
fun LazyListScope.carSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    item { Group("Car") }
    item {
        Toggle(
            "Continue from the desktop",
            "When the car starts and the computer played last, the car picks up its song, place and queue. Needs the Tonearm server.",
            settings.continueFromDesktop,
        ) { v -> update { it.copy(continueFromDesktop = v) } }
    }
}
