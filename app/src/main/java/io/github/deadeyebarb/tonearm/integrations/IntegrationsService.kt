package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.IntegrationsRepository
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.MalojaConfig
import io.github.deadeyebarb.tonearm.data.SecretBox
import java.io.IOException

class IntegrationNotConfiguredException(message: String) : IOException(message)

/** Maloja and Lidarr settings plus their clients, with API keys decrypted on demand. */
class IntegrationsService(
    val repository: IntegrationsRepository,
    val maloja: MalojaClient,
    val lidarr: LidarrClient,
    private val secrets: SecretBox,
) {
    val state get() = repository.state

    fun malojaKey(config: MalojaConfig): String = secrets.decrypt(config.keyEnc).orEmpty()
    fun lidarrKey(config: LidarrConfig): String = secrets.decrypt(config.keyEnc).orEmpty()

    suspend fun requireMaloja(): MalojaConfig =
        repository.current().maloja ?: throw IntegrationNotConfiguredException("Connect Maloja in Settings → Integrations first")

    suspend fun requireLidarrOrNull(): Pair<LidarrConfig, String>? = repository.current().lidarr?.let { it to lidarrKey(it) }

    suspend fun requireLidarr(): Pair<LidarrConfig, String> {
        val config = repository.current().lidarr ?: throw IntegrationNotConfiguredException("Connect Lidarr in Settings → Integrations first")
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
