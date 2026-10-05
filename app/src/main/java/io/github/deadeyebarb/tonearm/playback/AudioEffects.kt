package io.github.deadeyebarb.tonearm.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import androidx.media3.common.C
import io.github.deadeyebarb.tonearm.data.EqualizerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EqualizerInfo(
    val bandCenterHz: List<Int>,
    val minLevel: Int,
    val maxLevel: Int,
    val presets: List<String>,
)

/**
 * The built-in equalizer, attached to the player's audio session. Also announces the session
 * so system-wide equalizer apps (Wavelet, Poweramp EQ, Dolby…) can hook in. Main thread only.
 */
class AudioEffects(private val context: Context) {
    private var equalizer: Equalizer? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var settings = EqualizerSettings()

    private val _info = MutableStateFlow<EqualizerInfo?>(null)
    val info: StateFlow<EqualizerInfo?> = _info

    val audioSessionId: Int get() = sessionId

    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        release()
        sessionId = audioSessionId
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()?.also { eq ->
            val range = eq.bandLevelRange
            _info.value = EqualizerInfo(
                bandCenterHz = (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
            )
        }
        apply(settings)
    }

    fun apply(settings: EqualizerSettings) {
        this.settings = settings
        val eq = equalizer ?: return
        runCatching {
            if (settings.enabled) {
                if (settings.preset in 0 until eq.numberOfPresets) {
                    eq.usePreset(settings.preset.toShort())
                } else {
                    settings.bandLevels.forEachIndexed { band, level ->
                        if (band < eq.numberOfBands) eq.setBandLevel(band.toShort(), level.toShort())
                    }
                }
            }
            eq.enabled = settings.enabled
        }
    }

    /** Band levels a preset sets, so the sliders can show it. */
    fun levelsForPreset(preset: Int): List<Int>? {
        val eq = equalizer ?: return null
        return runCatching {
            eq.usePreset(preset.toShort())
            (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
        }.getOrNull()
    }

    fun release() {
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) broadcast(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        equalizer?.release()
        equalizer = null
        sessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun broadcast(action: String) {
        context.sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }
}
