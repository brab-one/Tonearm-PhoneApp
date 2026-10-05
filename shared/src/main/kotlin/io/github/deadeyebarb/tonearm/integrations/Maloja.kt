package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.MalojaConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Serializable
data class MalojaArtistEntry(val artist: String? = null, val scrobbles: Int = 0, val rank: Int = 0)

@Serializable
data class MalojaAlbum(val artists: List<String>? = null, val albumtitle: String? = null)

@Serializable
data class MalojaTrack(
    val artists: List<String> = emptyList(),
    val title: String = "",
    val album: MalojaAlbum? = null,
    val length: Int? = null,
)

@Serializable
data class MalojaTrackEntry(val track: MalojaTrack? = null, val scrobbles: Int = 0, val rank: Int = 0)

@Serializable
data class MalojaScrobble(val time: Long = 0, val track: MalojaTrack? = null, val duration: Int? = null)

@Serializable
private class MalojaList<T>(val status: String? = null, val list: List<T> = emptyList())

@Serializable
private class MalojaCount(val amount: Int = 0)

@Serializable
private class MalojaServerInfo(val name: String? = null, val versionstring: String? = null)

/**
 * Maloja's native API (`/apis/mlj_1/`). Charts take `since` as `YYYY/MM/DD` or keywords such as
 * `week`, `month`, `year`; `max` limits the list. Charts are public; scrobbling needs the key.
 */
class MalojaClient(private val http: IntegrationHttp, private val json: Json) {
    private fun url(config: MalojaConfig, endpoint: String, params: List<Pair<String, Any?>> = emptyList()) =
        IntegrationHttp.url(config.url, "apis/mlj_1/$endpoint", params)

    private suspend fun fetch(url: okhttp3.HttpUrl, useServerTls: Boolean) = http.get(url, useServerTls, service = "Maloja")

    /** Checks the server and the key; returns e.g. "Maloja 3.2.2". */
    suspend fun test(config: MalojaConfig, key: String): String {
        fetch(url(config, "test", listOf("key" to key.ifEmpty { null })), config.useServerTls)
        val info = runCatching {
            json.decodeFromString(MalojaServerInfo.serializer(), fetch(url(config, "serverinfo"), config.useServerTls))
        }.getOrNull()
        return listOfNotNull(info?.name?.takeIf { it.isNotBlank() } ?: "Maloja", info?.versionstring).joinToString(" ")
    }

    suspend fun topArtists(config: MalojaConfig, since: LocalDate?, max: Int): List<MalojaArtistEntry> {
        val body = fetch(url(config, "charts/artists", rangeParams(since) + ("max" to max)), config.useServerTls)
        return json.decodeFromString(MalojaList.serializer(MalojaArtistEntry.serializer()), body).list
    }

    suspend fun topTracks(config: MalojaConfig, since: LocalDate?, max: Int): List<MalojaTrackEntry> {
        val body = fetch(url(config, "charts/tracks", rangeParams(since) + ("max" to max)), config.useServerTls)
        return json.decodeFromString(MalojaList.serializer(MalojaTrackEntry.serializer()), body).list
    }

    suspend fun scrobbleCount(config: MalojaConfig, since: LocalDate?): Int {
        val body = fetch(url(config, "numscrobbles", rangeParams(since)), config.useServerTls)
        return json.decodeFromString(MalojaCount.serializer(), body).amount
    }

    /** Newest first. */
    suspend fun recentScrobbles(config: MalojaConfig, max: Int): List<MalojaScrobble> {
        val body = fetch(url(config, "scrobbles", listOf("max" to max)), config.useServerTls)
        return json.decodeFromString(MalojaList.serializer(MalojaScrobble.serializer()), body).list
    }

    suspend fun scrobble(
        config: MalojaConfig,
        key: String,
        artists: List<String>,
        title: String,
        album: String?,
        listenedSeconds: Int?,
        lengthSeconds: Int?,
        timeSeconds: Long,
    ) {
        val body = buildJsonObject {
            put("key", key)
            putJsonArray("artists") { artists.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("title", title)
            album?.let { put("album", it) }
            listenedSeconds?.let { put("duration", it) }
            lengthSeconds?.let { put("length", it) }
            put("time", timeSeconds)
        }
        http.postJson(url(config, "newscrobble"), body.toString(), config.useServerTls, service = "Maloja")
    }

    private fun rangeParams(since: LocalDate?): List<Pair<String, Any?>> =
        if (since == null) listOf("in" to "alltime") else listOf("since" to since.format(DATE))

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    }
}
