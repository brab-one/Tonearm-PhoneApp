package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.data.Integrations
import io.github.deadeyebarb.tonearm.data.IntegrationsRepository
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.MalojaConfig
import io.github.deadeyebarb.tonearm.data.SecretBox
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class IntegrationNotConfiguredException(message: String) : IOException(message)

/**
 * Maloja and Lidarr plus their clients, with API keys decrypted on demand. When the Tonearm server at the
 * music server's address offers them, they go through it (it holds the keys); otherwise the settings here.
 */
class IntegrationsService(
    val repository: IntegrationsRepository,
    val maloja: MalojaClient,
    val lidarr: LidarrClient,
    private val secrets: SecretBox,
    private val server: ConnectRouter,
    private val sessions: SessionManager,
    scope: CoroutineScope,
) {
    /** What the app uses; the settings screens edit [repository] itself. */
    val state: StateFlow<Integrations> = combine(repository.state, server.server) { stored, offer -> stored.through(offer) }
        .stateIn(scope, SharingStarted.Eagerly, repository.state.value.through(server.server.value))

    init {
        // Notice when the Tonearm server appears, goes, or the music server changes.
        scope.launch {
            sessions.active.collectLatest { session ->
                while (true) {
                    server.refresh(session)
                    delay(5 * 60_000L)
                }
            }
        }
    }

    /** Like [state], looking the Tonearm server up first if that's due (e.g. in a background job). */
    suspend fun current(): Integrations {
        val session = sessions.active.value ?: try {
            sessions.awaitActive()
        } catch (_: NoServerException) {
            null
        }
        return repository.current().through(server.refresh(session))
    }

    fun malojaKey(config: MalojaConfig): String = secrets.decrypt(config.keyEnc).orEmpty()
    fun lidarrKey(config: LidarrConfig): String = secrets.decrypt(config.keyEnc).orEmpty()

    suspend fun requireMaloja(): MalojaConfig =
        current().maloja ?: throw IntegrationNotConfiguredException("Connect Maloja in Settings → Integrations first")

    suspend fun requireLidarrOrNull(): Pair<LidarrConfig, String>? = current().lidarr?.let { it to lidarrKey(it) }

    suspend fun requireLidarr(): Pair<LidarrConfig, String> {
        val config = current().lidarr ?: throw IntegrationNotConfiguredException("Connect Lidarr in Settings → Integrations first")
        return config to lidarrKey(config)
    }

    /** Asks Lidarr to get [candidate] (artist or album) with the saved defaults. */
    suspend fun request(candidate: LidarrCandidate) {
        val (config, key) = requireLidarr()
        val defaults = lidarr.resolveDefaults(config, key)
        if (candidate.isAlbum) lidarr.addAlbum(config, key, candidate, defaults) else lidarr.addArtist(config, key, candidate, defaults)
    }

    enum class ArtistRequestResult { REQUESTED, ALREADY_IN_LIDARR, NOT_FOUND }

    /**
     * Requests an artist known only by name: looks it up on MusicBrainz through Lidarr, takes the
     * exact-name match (or, unless [exactOnly], the best hit) and adds it with [monitor] instead of
     * the saved default.
     */
    suspend fun requestArtistByName(name: String, monitor: String? = null, exactOnly: Boolean = false): ArtistRequestResult {
        val (config, key) = requireLidarr()
        val candidates = lidarr.lookupArtist(config, key, name)
        val exact = candidates.firstOrNull { Recommender.normalize(it.title) == Recommender.normalize(name) }
        val pick = exact ?: candidates.firstOrNull()?.takeUnless { exactOnly }
            ?: return ArtistRequestResult.NOT_FOUND
        if (pick.inLidarr) return ArtistRequestResult.ALREADY_IN_LIDARR
        val defaults = lidarr.resolveDefaults(config, key).let { d -> if (monitor != null) d.copy(monitor = monitor) else d }
        return try {
            lidarr.addArtist(config, key, pick, defaults)
            ArtistRequestResult.REQUESTED
        } catch (e: IntegrationHttpException) {
            if ("already" in e.message.orEmpty().lowercase()) ArtistRequestResult.ALREADY_IN_LIDARR else throw e
        }
    }

    fun encrypt(secret: String) = secrets.encrypt(secret)
    fun decrypt(secretEnc: String) = secrets.decrypt(secretEnc).orEmpty()
}
