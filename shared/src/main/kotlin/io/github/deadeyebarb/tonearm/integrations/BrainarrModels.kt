package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.subsonic.Artist
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A Brainarr import list in Lidarr, with the settings worth showing. */
data class BrainarrList(
    val id: Int,
    val name: String,
    val provider: String?,
    val model: String?,
    val discoveryMode: String?,
    val albumMode: Boolean,
    val perRun: Int?,
    val automaticAdd: Boolean,
    val tags: List<Int>,
    val raw: JsonObject,
) {
    val summary: String
        get() = listOfNotNull(
            provider?.let(::humanize),
            model,
            discoveryMode?.let { humanize(it) + " discovery" },
            perRun?.let { "$it ${if (albumMode) "albums" else "artists"} per run" },
        ).joinToString(" · ")

    companion object {
        const val IMPLEMENTATION = "Brainarr"

        fun from(list: LidarrImportList, raw: JsonObject) = BrainarrList(
            id = list.id,
            name = list.name,
            provider = list.field("provider"),
            model = list.field("modelSelection")?.takeIf { it.isNotBlank() },
            discoveryMode = list.field("discoveryMode"),
            albumMode = list.field("recommendationMode")?.lowercase()?.contains("album") ?: true,
            perRun = list.field("maxRecommendations")?.toIntOrNull(),
            automaticAdd = list.enableAutomaticAdd,
            tags = list.tags,
            raw = raw,
        )

        private val names = mapOf(
            "lmstudio" to "LM Studio", "openai" to "OpenAI", "deepseek" to "DeepSeek", "openrouter" to "OpenRouter",
            "zaiglm" to "Z.AI GLM", "zaicoding" to "Z.AI Coding", "claudecode" to "Claude Code", "openaicodex" to "OpenAI Codex",
        )

        /** "lmStudio" → "LM Studio", "adjacent" → "Adjacent"; numbers (older enum values) are dropped. */
        fun humanize(value: String): String? {
            if (value.isBlank() || value.all { it.isDigit() }) return null
            names[value.lowercase()]?.let { return it }
            return value.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replaceFirstChar { it.uppercase() }
        }
    }
}

enum class PickStatus { IN_LIBRARY, DOWNLOADING, ON_DISK, WANTED, NOT_MONITORED }

/** An artist Brainarr added to Lidarr, and how far it is from being playable. */
data class BrainarrPick(
    val lidarrId: Int,
    val name: String,
    val genres: List<String>,
    val added: Instant?,
    val status: PickStatus,
    val tracksOnDisk: Int,
    val tracksWanted: Int,
    /** Download progress while [status] is [PickStatus.DOWNLOADING]. */
    val progress: Float?,
    val imageUrl: String?,
    /** The artist on the music server, once it has been downloaded and scanned. */
    val libraryArtist: Artist?,
)

data class BrainarrData(
    val lists: List<BrainarrList>,
    val picks: List<BrainarrPick>,
) {
    /** Picks can be told apart from your own additions when every list tags what it adds. */
    val labelled: Boolean get() = lists.isNotEmpty() && lists.all { it.tags.isNotEmpty() }
    val inLibrary: List<BrainarrPick> get() = picks.filter { it.libraryArtist != null }
}

data class AskResult(val added: List<String>, val message: String?)

/** Which Lidarr artists are Brainarr's picks, and how far each is from being playable. */
object BrainarrPicks {
    /** Artists carrying one of [tags] or recorded from a run, newest first. */
    fun pickArtists(artists: List<LidarrArtist>, tags: Set<Int>, recorded: Set<String>): List<LidarrArtist> =
        artists.filter { artist -> artist.tags.any { it in tags } || artist.foreignArtistId in recorded }
            .sortedByDescending { it.added.orEmpty() }

    fun status(artist: LidarrArtist, inLibrary: Boolean, downloading: Boolean): PickStatus {
        val stats = artist.statistics
        val onDisk = stats?.trackFileCount ?: 0
        val wanted = stats?.trackCount ?: 0
        return when {
            inLibrary -> PickStatus.IN_LIBRARY
            downloading -> PickStatus.DOWNLOADING
            onDisk > 0 -> PickStatus.ON_DISK
            artist.monitored && (stats == null || wanted > 0) -> PickStatus.WANTED
            else -> PickStatus.NOT_MONITORED
        }
    }
}
