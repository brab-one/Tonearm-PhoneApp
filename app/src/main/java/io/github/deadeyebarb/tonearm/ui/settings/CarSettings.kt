package io.github.deadeyebarb.tonearm.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.AppSettings
import io.github.deadeyebarb.tonearm.download.OfflineSync
import io.github.deadeyebarb.tonearm.download.OfflineSyncWorker
import io.github.deadeyebarb.tonearm.ui.common.formatBytes
import io.github.deadeyebarb.tonearm.ui.common.plural

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

/** Settings → Downloads: Keep offline, its space and how the last run went. */
fun LazyListScope.keepOfflineSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    item {
        val context = LocalContext.current
        Toggle(
            "Keep offline",
            "Every night on Wi-Fi while charging, your weekly picks and liked songs are downloaded, for drives without signal. Your own downloads stay as they are.",
            settings.keepOffline,
        ) { v ->
            update { it.copy(keepOffline = v) }
            OfflineSyncWorker.schedule(context, v)
            // The first run doesn't wait for the night.
            if (v) OfflineSyncWorker.now(context)
        }
    }
    if (settings.keepOffline) {
        item { KeepOfflineSpace(settings.keepOfflineGb) { gb -> update { it.copy(keepOfflineGb = gb) } } }
        item { KeepOfflineStatus() }
    }
}

@Composable
private fun KeepOfflineSpace(gb: Int, onChange: (Int) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Item("Space for kept songs", formatBytes(gb * OfflineSync.GB)) { choosing = true }
    if (choosing) ChoiceDialog("Space for kept songs", listOf(1, 2, 4, 8, 16), gb, { formatBytes(it * OfflineSync.GB) }, onChange) { choosing = false }
}

/** "Last kept 03:12 · 212 songs · 3.6 GB", or that it's waiting for its first night. */
@Composable
private fun KeepOfflineStatus() {
    val context = LocalContext.current
    val c = context.container
    val state by c.offline.state.collectAsStateWithLifecycle()
    val entries by c.downloads.entries.collectAsStateWithLifecycle()
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    val kept = state.kept.mapNotNull { entries[it] }.filter { it.completed }
    val status = if (state.lastRun == 0L) {
        "Waiting for Wi-Fi and a charger"
    } else {
        val flags = if (DateUtils.isToday(state.lastRun)) {
            DateUtils.FORMAT_SHOW_TIME
        } else {
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        }
        listOfNotNull(
            "Last kept " + DateUtils.formatDateTime(context, state.lastRun, flags),
            plural(kept.size, "song"),
            formatBytes(kept.sumOf { it.bytes }),
            state.result,
        ).joinToString(" · ")
    }
    // The AI on the Tonearm server makes the weekly picks.
    val note = "Weekly picks need the Tonearm server".takeIf { server?.recommendations != true }
    Item("Kept offline", listOfNotNull(status, note).joinToString("\n"))
}
