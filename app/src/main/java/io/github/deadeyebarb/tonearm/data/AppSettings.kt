package io.github.deadeyebarb.tonearm.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class StreamQuality(val maxBitRate: Int) {
    ORIGINAL(0), KBPS_320(320), KBPS_256(256), KBPS_192(192), KBPS_160(160), KBPS_128(128), KBPS_96(96);

    val label: String get() = if (this == ORIGINAL) "Original (lossless)" else "$maxBitRate kbps"
}

@Serializable
enum class TranscodeFormat(val param: String, val label: String) {
    MP3("mp3", "MP3"), OPUS("opus", "Opus"), AAC("aac", "AAC"),
}

@Serializable
enum class ReplayGainMode(val label: String) {
    OFF("Off"), TRACK("Track"), ALBUM("Album"),
}

/** Neon accent pairs for the HUD theme. [ARTWORK] follows the cover of the song that's playing. */
@Serializable
enum class AccentColor(val label: String, val primary: Long, val secondary: Long) {
    CYAN("Plasma cyan", 0xFF00E5FF, 0xFFFF2BD6),
    MAGENTA("Synth magenta", 0xFFFF3DDB, 0xFF00E5FF),
    AMBER("Reactor amber", 0xFFFFB627, 0xFF00C2FF),
    LIME("Terminal green", 0xFF6BFF5C, 0xFF00E5FF),
    VIOLET("Ion violet", 0xFFA875FF, 0xFF3DFFD2),
    ARTWORK("Album art", 0xFF00E5FF, 0xFFFF2BD6),
}

@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** Index of a built-in preset, or -1 for custom band levels. */
    val preset: Int = -1,
    /** Band levels in millibels, one per band. */
    val bandLevels: List<Int> = emptyList(),
)

@Serializable
data class AppSettings(
    val wifiQuality: StreamQuality = StreamQuality.ORIGINAL,
    val mobileQuality: StreamQuality = StreamQuality.ORIGINAL,
    val transcodeFormat: TranscodeFormat = TranscodeFormat.MP3,
    val streamCacheMb: Int = 2048,
    /** How many of the next songs in the queue to cache while one plays (0 = off). */
    val cacheAhead: Int = 3,
    /** Songs that aren't on your server play from YouTube Music. */
    val youtubeFallback: Boolean = true,
    /** Artists you play from YouTube Music are requested in Lidarr, so the server gets the lossless version. */
    val requestWhatYouPlay: Boolean = true,
    val downloadOnWifiOnly: Boolean = true,
    val hiResOutput: Boolean = true,
    val audioOffload: Boolean = false,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val replayGainPreampDb: Float = 0f,
    val scrobble: Boolean = true,
    val accent: AccentColor = AccentColor.CYAN,
    val pureBlack: Boolean = false,
    val visualizer: Boolean = true,
    val equalizer: EqualizerSettings = EqualizerSettings(),
)

class SettingsRepository(context: Context, json: Json, private val scope: CoroutineScope) {
    private val store = jsonDataStore(context, "settings", AppSettings.serializer(), AppSettings(), json)

    val state: StateFlow<AppSettings> = store.data.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = store.data.first()

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { store.updateData(transform) }
    }
}
