package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.net.forImages
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The image loader's network calls. Lidarr's cover API (`api/v1/mediacover/…` under the configured
 * Lidarr address) goes through the client and API key the integration uses; everything else goes
 * to [fallback]. The key is only ever sent to that one address.
 */
class IntegrationImageCalls(
    private val integrations: IntegrationsService,
    private val base: OkHttpClient,
    /** The music server's client, when its settings are loaded. */
    private val serverClient: () -> OkHttpClient?,
    private val fallback: Call.Factory,
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val lidarr = integrations.state.value.lidarr
        if (lidarr != null && isLidarrCover(lidarr.url, request.url)) {
            val client = if (lidarr.useServerTls) serverClient() ?: base else base
            return client.forImages().newCall(request.newBuilder().header("X-Api-Key", integrations.lidarrKey(lidarr)).build())
        }
        return fallback.newCall(request)
    }

    companion object {
        fun isLidarrCover(lidarrUrl: String, url: HttpUrl): Boolean = LidarrClient.isCoverUrl(lidarrUrl, url)
    }
}
