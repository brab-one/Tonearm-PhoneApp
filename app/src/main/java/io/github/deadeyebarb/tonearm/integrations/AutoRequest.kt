package io.github.deadeyebarb.tonearm.integrations

import android.content.Context
import io.github.deadeyebarb.tonearm.data.Messages
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.data.jsonDataStore
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class AutoRequestState(
    /** Normalized artist names already handled (requested, or already in Lidarr). */
    val handled: List<String> = emptyList(),
)

/**
 * When a song plays from YouTube Music, asks Lidarr for its artist (with the usual request settings),
 * so the server gets the lossless version and later plays come from the library.
 */
class AutoRequest(
    context: Context,
    json: Json,
    private val scope: CoroutineScope,
    private val integrations: IntegrationsService,
    private val settings: SettingsRepository,
    private val messages: Messages,
) {
    private val store = jsonDataStore(context, "auto_request", AutoRequestState.serializer(), AutoRequestState(), json)
    private val mutex = Mutex()

    fun onPlaying(song: Song) {
        if (!settings.state.value.requestWhatYouPlay || integrations.state.value.lidarr == null) return
        val credited = song.artist?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            mutex.withLock {
                val names = candidates(credited)
                val handled = store.data.first().handled.toSet()
                if (names.any { Recommender.normalize(it) in handled }) return@withLock
                try {
                    request(names)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    messages.show("Couldn't request $credited: ${e.userMessage()}")
                }
            }
        }
    }

    private suspend fun request(names: List<String>) {
        for (name in names) {
            val result = integrations.requestArtistByName(name, exactOnly = true)
            if (result == IntegrationsService.ArtistRequestResult.NOT_FOUND) continue
            remember(name)
            if (result == IntegrationsService.ArtistRequestResult.REQUESTED) messages.show("Requested $name in Lidarr")
            return
        }
        // No exact match: don't guess and add the wrong artist. Asked again next time it plays.
        messages.show("Lidarr has no exact match for ${names.first()}")
    }

    private suspend fun remember(name: String) {
        store.updateData { state -> state.copy(handled = (state.handled + Recommender.normalize(name)).distinct().takeLast(MAX_HANDLED)) }
    }

    companion object {
        private const val MAX_HANDLED = 5000

        /** "A, B & C" → the full credit first, then the first artist alone. */
        fun candidates(credited: String): List<String> {
            val first = credited.split(Regex("\\s*(?:,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE))
                .firstOrNull { it.isNotBlank() }?.trim()
            return listOfNotNull(credited.trim(), first).distinct()
        }
    }
}
