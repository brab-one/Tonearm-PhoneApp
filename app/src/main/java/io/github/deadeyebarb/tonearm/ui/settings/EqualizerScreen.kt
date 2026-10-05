package io.github.deadeyebarb.tonearm.ui.settings

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.EqualizerSettings
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold

@Composable
fun EqualizerScreen() {
    val context = LocalContext.current
    val c = context.container
    val info by c.effects.info.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val eq = settings.equalizer
    val locale = LocalConfiguration.current.locales[0]
    fun save(value: EqualizerSettings) = c.settings.update { it.copy(equalizer = value) }

    val systemPanel = remember {
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    val hasSystemPanel = remember { systemPanel.resolveActivity(context.packageManager) != null }

    DetailScaffold(title = "Equalizer") {
        val bands = info
        if (bands == null) {
            EmptyState(
                Icons.Rounded.Equalizer, "Equalizer not available",
                "Start playing something, then come back. Some devices don't provide an equalizer.",
            )
            return@DetailScaffold
        }
        val levels = eq.bandLevels.takeIf { it.size == bands.bandCenterHz.size } ?: List(bands.bandCenterHz.size) { 0 }
        var local by remember(levels) { mutableStateOf(levels) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Use equalizer") },
                trailingContent = { Switch(eq.enabled, { save(eq.copy(enabled = it, bandLevels = levels)) }) },
            )
            if (settings.audioOffload) {
                Text(
                    "Audio offload is on, so the equalizer may be bypassed. Turn it off in Settings for reliable EQ.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = eq.preset < 0, enabled = eq.enabled, onClick = { save(eq.copy(preset = -1)) }, label = { Text("Custom") })
                }
                itemsIndexed(bands.presets) { i, name ->
                    FilterChip(
                        selected = eq.preset == i, enabled = eq.enabled,
                        onClick = { save(eq.copy(preset = i, bandLevels = c.effects.levelsForPreset(i) ?: levels)) },
                        label = { Text(name) },
                    )
                }
            }
            bands.bandCenterHz.forEachIndexed { band, hz ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hz >= 1000) String.format(locale, "%.1f kHz", hz / 1000f) else "$hz Hz",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp),
                    )
                    Slider(
                        value = local[band].toFloat(),
                        onValueChange = { v ->
                            local = local.toMutableList().also { it[band] = v.toInt() }
                            c.effects.apply(eq.copy(preset = -1, bandLevels = local))
                        },
                        onValueChangeFinished = { save(eq.copy(preset = -1, bandLevels = local)) },
                        valueRange = bands.minLevel.toFloat()..bands.maxLevel.toFloat(),
                        enabled = eq.enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format(locale, "%+.1f", local[band] / 100f),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp),
                    )
                }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { save(eq.copy(preset = -1, bandLevels = List(bands.bandCenterHz.size) { 0 })) }, enabled = eq.enabled) {
                    Text("Reset")
                }
                if (hasSystemPanel) {
                    OutlinedButton(onClick = {
                        context.startActivity(systemPanel.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, c.effects.audioSessionId))
                    }) { Text("System equalizer") }
                }
            }
        }
    }
}
