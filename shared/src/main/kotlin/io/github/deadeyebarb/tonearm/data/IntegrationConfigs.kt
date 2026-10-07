package io.github.deadeyebarb.tonearm.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    val lidarr: LidarrConfig? = null,
) {
    /**
     * Lidarr as the app uses it: the Tonearm server's when it offers it (it holds the key, so the music server
     * login is enough). It only comes that way; the settings here only hold its request preferences.
     */
    fun through(server: TonearmServerInfo?): Integrations = Integrations(
        lidarr = if (server?.lidarr == true) {
            (lidarr ?: LidarrConfig(url = "")).copy(url = server.serviceUrl("lidarr"), keyEnc = "", useServerTls = true, viaServer = true, limited = !server.lidarrAdmin)
        } else null,
    )
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
    /** Album suggestions and search from its AI. */
    val recommendations: Boolean = false,
    /** Discovery picks and similar artists (from Deezer). */
    val discovery: Boolean = false,
    /** It keeps the listening history: the apps tell it what they played. */
    val history: Boolean = false,
    /** Which AI answers ("Claude claude-opus-5-5"), when there is one. */
    val ai: String? = null,
) {
    fun serviceUrl(service: String) = baseUrl.trimEnd('/') + "/connect-tonearm/" + service
}
