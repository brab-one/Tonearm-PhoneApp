package io.github.deadeyebarb.tonearm.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** A self-hosted Maloja scrobble server: the source of listening history for recommendations. */
@Serializable
data class MalojaConfig(
    val url: String,
    /** Keystore-encrypted API key (Maloja → Settings → API keys). */
    val keyEnc: String = "",
    /** Send plays to Maloja directly. Off by default: many servers already forward scrobbles to it. */
    val scrobble: Boolean = false,
    /** Reuse the music server's client certificate and trusted CA (same mTLS proxy). */
    val useServerTls: Boolean = true,
    /** Reached through the Tonearm server, which holds the key. */
    @Transient val viaServer: Boolean = false,
)

/** Lidarr, for requesting music that isn't in the library yet. */
@Serializable
data class LidarrConfig(
    val url: String,
    val keyEnc: String = "",
    /** Null means "the first root folder Lidarr has". */
    val rootFolderPath: String? = null,
    /** Null means the root folder's default (or Lidarr's first profile). */
    val qualityProfileId: Int? = null,
    val metadataProfileId: Int? = null,
    /** Which albums of a requested artist to monitor: all, future, missing, latest, first, none. */
    val monitor: String = "all",
    /** Start searching for downloads right after a request. */
    val searchOnAdd: Boolean = true,
    val useServerTls: Boolean = true,
    /** Reached through the Tonearm server, which holds the key. */
    @Transient val viaServer: Boolean = false,
    /** Through the Tonearm server without admin rights there: look up, request and see downloads only. */
    @Transient val limited: Boolean = false,
)

@Serializable
data class Integrations(
    val maloja: MalojaConfig? = null,
    val lidarr: LidarrConfig? = null,
) {
    /**
     * Lidarr and Maloja as the app uses them: the Tonearm server's when it offers them (it holds their
     * keys, so the music server login is enough), else these settings. Request preferences are kept.
     */
    fun through(server: TonearmServerInfo?): Integrations {
        if (server == null) return this
        return Integrations(
            maloja = if (server.maloja) {
                (maloja ?: MalojaConfig(url = "")).copy(url = server.serviceUrl("maloja"), keyEnc = "", useServerTls = true, viaServer = true)
            } else maloja,
            lidarr = if (server.lidarr) {
                (lidarr ?: LidarrConfig(url = "")).copy(url = server.serviceUrl("lidarr"), keyEnc = "", useServerTls = true, viaServer = true, limited = !server.lidarrAdmin)
            } else lidarr,
        )
    }
}

/** What the Tonearm server at the music server's address offers this user (its hello). */
data class TonearmServerInfo(
    /** The music server address it was found at. */
    val baseUrl: String,
    val version: String = "",
    val user: String = "",
    val admin: Boolean = false,
    val lidarr: Boolean = false,
    val lidarrAdmin: Boolean = false,
    val maloja: Boolean = false,
    /** Album suggestions from its Ollama. */
    val recommendations: Boolean = false,
) {
    fun serviceUrl(service: String) = baseUrl.trimEnd('/') + "/connect-tonearm/" + service
}
