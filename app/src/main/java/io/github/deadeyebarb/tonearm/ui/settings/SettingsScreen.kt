package io.github.deadeyebarb.tonearm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.Album
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.BuildConfig
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.AppSettings
import io.github.deadeyebarb.tonearm.data.QueueEnd
import io.github.deadeyebarb.tonearm.data.ReplayGainMode
import io.github.deadeyebarb.tonearm.data.StreamQuality
import io.github.deadeyebarb.tonearm.data.AccentColor
import io.github.deadeyebarb.tonearm.data.TranscodeFormat
import io.github.deadeyebarb.tonearm.ui.EqualizerRoute
import io.github.deadeyebarb.tonearm.ui.LidarrSettingsRoute
import io.github.deadeyebarb.tonearm.ui.MalojaSettingsRoute
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import io.github.deadeyebarb.tonearm.ui.ServersRoute
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.formatBytes
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val servers by c.servers.state.collectAsStateWithLifecycle()
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    fun update(transform: (AppSettings) -> AppSettings) = c.settings.update(transform)
    fun <T> choose(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
        dialog = { ChoiceDialog(title, options, selected, label, onSelect) { dialog = null } }
    }

    DetailScaffold(title = "Settings") {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Group("Server") }
            item {
                val active = servers?.active
                Item(
                    "Servers", active?.let { "${it.name} · ${it.baseUrl}" } ?: "None set up",
                    icon = { Icon(Icons.Rounded.Dns, null) },
                ) { actions.navigate(ServersRoute) }
            }

            item { Group("Integrations") }
            item {
                val maloja = integrations.maloja
                Item(
                    "Maloja",
                    maloja?.let { (if (it.viaServer) "Through the Tonearm server" else "Recommendations from ${it.url}") + if (it.scrobble) " · sending plays" else "" }
                        ?: "Not connected: recommendations from your scrobbles",
                    icon = { Icon(Icons.Rounded.AutoAwesome, null) },
                ) { actions.navigate(MalojaSettingsRoute) }
            }
            item {
                Item(
                    "Lidarr", integrations.lidarr?.let { if (it.viaServer) "Through the Tonearm server" else "Requests go to ${it.url}" } ?: "Not connected: request music you don't have",
                    icon = { Icon(Icons.Rounded.CloudDownload, null) },
                ) { actions.navigate(LidarrSettingsRoute) }
            }

            item { Group("Streaming") }
            item {
                Item("Quality on Wi-Fi", settings.wifiQuality.label) {
                    choose("Quality on Wi-Fi", StreamQuality.entries, settings.wifiQuality, { it.label }) { q -> update { it.copy(wifiQuality = q) } }
                }
            }
            item {
                Item("Quality on mobile data", settings.mobileQuality.label) {
                    choose("Quality on mobile data", StreamQuality.entries, settings.mobileQuality, { it.label }) { q -> update { it.copy(mobileQuality = q) } }
                }
            }
            item {
                Item("Transcoding format", "${settings.transcodeFormat.label} — used when a quality other than Original is chosen") {
                    choose("Transcoding format", TranscodeFormat.entries, settings.transcodeFormat, { it.label }) { f -> update { it.copy(transcodeFormat = f) } }
                }
            }
            item {
                val sizes = listOf(512, 1024, 2048, 4096, 8192, 16384)
                Item("Streaming cache", "${formatBytes(settings.streamCacheMb * 1024L * 1024L)} for recently played songs") {
                    choose("Streaming cache", sizes, settings.streamCacheMb, { formatBytes(it * 1024L * 1024L) }) { mb -> update { it.copy(streamCacheMb = mb) } }
                }
            }
            item {
                val counts = listOf(0, 1, 3, 5, 10, 25, AppSettings.CACHE_WHOLE_QUEUE)
                fun label(n: Int) = when (n) {
                    0 -> "Off"
                    1 -> "1 song"
                    AppSettings.CACHE_WHOLE_QUEUE -> "The whole queue"
                    else -> "$n songs"
                }
                Item(
                    "Cache ahead",
                    when (settings.cacheAhead) {
                        0 -> "Off"
                        AppSettings.CACHE_WHOLE_QUEUE -> "The rest of the queue (the album or playlist playing) is cached while you listen"
                        else -> "The next ${settings.cacheAhead} songs in the queue are cached while one plays"
                    },
                ) {
                    choose("Cache ahead", counts, settings.cacheAhead, ::label) { n ->
                        update { it.copy(cacheAhead = n) }
                    }
                }
            }
            item {
                Item("Clear streaming cache", "Downloads are not affected") {
                    c.scope.launch(Dispatchers.IO) {
                        c.media.clearStreamCache()
                        c.messages.show("Streaming cache cleared")
                    }
                }
            }

            item { Group("YouTube Music") }
            item {
                Toggle(
                    "Play what you don't have",
                    "Recommendations and search results that aren't on your server play from YouTube Music (Opus, up to 160 kbps).",
                    settings.youtubeFallback,
                ) { v -> update { it.copy(youtubeFallback = v) } }
            }
            item {
                Toggle(
                    "YouTube Music artists and albums",
                    "Search and artist pages also show artists and albums from YouTube Music: bios, popular songs and albums you don't have, playable and requestable.",
                    settings.youtubeCatalog,
                ) { v -> update { it.copy(youtubeCatalog = v) } }
            }
            item {
                Toggle(
                    "Request songs you like",
                    if (integrations.lidarr != null) {
                        "Liking a YouTube Music song asks Lidarr for its album, so your server gets the lossless version. Just playing requests nothing."
                    } else {
                        "Needs Lidarr: liking a YouTube Music song asks Lidarr for its album. Just playing requests nothing."
                    },
                    settings.requestLikes,
                ) { v -> update { it.copy(requestLikes = v) } }
            }

            item {
                Item("When the queue ends", "${settings.whenQueueEnds.label}: ${settings.whenQueueEnds.description}") {
                    choose("When the queue ends", QueueEnd.entries, settings.whenQueueEnds, { it.label }) { v -> update { it.copy(whenQueueEnds = v) } }
                }
            }

            item { Group("Playback") }
            item {
                Toggle(
                    "Hi-res output", "32-bit float output keeps 24-bit audio at full resolution. Applies the next time the player starts.",
                    settings.hiResOutput,
                ) { v -> update { it.copy(hiResOutput = v) } }
            }
            item {
                Toggle(
                    "Audio offload", "Let the device's audio DSP decode with the screen off to save battery. The equalizer may stop working.",
                    settings.audioOffload,
                ) { v -> update { it.copy(audioOffload = v) } }
            }
            item {
                Item("ReplayGain", settings.replayGain.label + " — evens out loudness using your files' ReplayGain tags") {
                    choose("ReplayGain", ReplayGainMode.entries, settings.replayGain, { it.label }) { m -> update { it.copy(replayGain = m) } }
                }
            }
            if (settings.replayGain != ReplayGainMode.OFF) {
                item { PreampSlider(settings.replayGainPreampDb) { db -> update { it.copy(replayGainPreampDb = db) } } }
            }
            item {
                Item("Equalizer", if (settings.equalizer.enabled) "On" else "Off", icon = { Icon(Icons.Rounded.Equalizer, null) }) {
                    actions.navigate(EqualizerRoute)
                }
            }

            item { Group("Downloads") }
            item {
                Toggle("Download on Wi-Fi only", "Wait for an unmetered network before downloading", settings.downloadOnWifiOnly) { v ->
                    update { it.copy(downloadOnWifiOnly = v) }
                }
            }
            item {
                val used = remember { c.downloads.bytesUsed() }
                Item("Storage used", formatBytes(used))
            }

            item { Group("Scrobbling") }
            item {
                Toggle(
                    "Scrobble plays", "Tell the server what you play, for play counts and Last.fm / ListenBrainz if it's linked there",
                    settings.scrobble,
                ) { v -> update { it.copy(scrobble = v) } }
            }

            item { Group("Appearance") }
            item { AccentPicker(settings.accent) { a -> update { it.copy(accent = a) } } }
            item {
                Toggle("Visualizer", "Live spectrum around the cover and in the mini player. Uses a little extra battery.", settings.visualizer) { v ->
                    update { it.copy(visualizer = v) }
                }
            }
            item { Toggle("Pure black", "True black background, for OLED screens", settings.pureBlack) { v -> update { it.copy(pureBlack = v) } } }

            item { Group("About") }
            item { Item("Tonearm ${BuildConfig.VERSION_NAME}", "Subsonic & OpenSubsonic player with mTLS support") }
        }
    }
    dialog?.invoke()
}

@Composable
private fun Group(title: String) = HudSectionHeader(title)

/** Neon swatches; the selected one glows. */
@Composable
private fun AccentPicker(selected: AccentColor, onSelect: (AccentColor) -> Unit) {
    val hud = Hud.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Accent color", style = MaterialTheme.typography.bodyLarge)
        Text(selected.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.accent)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (accent in AccentColor.entries) {
                val primary = Color(accent.primary)
                val secondary = Color(accent.secondary)
                val shape = MaterialTheme.shapes.small
                val isSelected = accent == selected
                Box(
                    Modifier
                        .size(42.dp)
                        .then(if (isSelected) Modifier.glowBorder(primary, shape, width = 2.dp, glow = 10.dp) else Modifier.border(1.dp, hud.line, shape))
                        .clip(shape)
                        .background(
                            if (accent == AccentColor.ARTWORK) {
                                Brush.sweepGradient(listOf(Color(0xFFFF2BD6), Color(0xFFFFB627), Color(0xFF6BFF5C), Color(0xFF00E5FF), Color(0xFFA875FF), Color(0xFFFF2BD6)))
                            } else {
                                Brush.linearGradient(listOf(primary, secondary))
                            },
                        )
                        .selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(accent) })
                        .semantics { contentDescription = accent.label },
                    contentAlignment = Alignment.Center,
                ) {
                    if (accent == AccentColor.ARTWORK) Icon(Icons.Rounded.Album, null, tint = Color.Black.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun Item(title: String, subtitle: String? = null, icon: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
    )
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun PreampSlider(value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pre-amp", modifier = Modifier.weight(1f))
            Text(String.format(locale, "%+.1f dB", local), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = -12f..6f, steps = 35)
        Text(
            "Songs are never made louder than their original level, so a positive pre-amp only lifts quieter tracks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (option in options) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onSelect(option)
                            onDismiss()
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
