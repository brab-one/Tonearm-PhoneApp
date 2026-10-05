package io.github.deadeyebarb.tonearm.data

import kotlinx.serialization.Serializable

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
)

@Serializable
data class Integrations(
    val maloja: MalojaConfig? = null,
    val lidarr: LidarrConfig? = null,
)
