package io.github.deadeyebarb.tonearm.connect

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A song as devices pass it around: enough to play it from the same server, and to show it. */
@Serializable
data class ConnectSong(
    val id: String,
    /** [SERVER] for the music server (ids are the server's), [YOUTUBE] for YouTube Music video ids. */
    val source: String = SERVER,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
) {
    companion object {
        const val SERVER = "server"
        const val YOUTUBE = "ytmusic"
        /** A file on the desktop that plays it (the id is its path); other devices can only show it. */
        const val LOCAL = "local"
    }
}

/** A library (or YouTube Music) song as a Connect song. */
fun Song.toConnectSong(source: String = ConnectSong.SERVER): ConnectSong = ConnectSong(
    id = id, source = source, title = title, artist = artistLabel.ifEmpty { null }, album = album, albumId = albumId,
    artistId = artistId, coverArt = coverArt, duration = duration, suffix = suffix, bitRate = bitRate, bitDepth = bitDepth,
    samplingRate = samplingRate,
)

@Serializable
data class PlaybackState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Wall-clock time [positionMs] was read at, so others can move the position on while it plays. */
    val at: Long = 0,
    val index: Int = -1,
    val queue: List<ConnectSong> = emptyList(),
    /** 0–100. */
    val volume: Int = 100,
    val shuffle: Boolean = false,
    /** "off", "all" or "one". */
    val repeat: String = "off",
) {
    val current: ConnectSong? get() = queue.getOrNull(index)

    fun positionAt(now: Long = System.currentTimeMillis()): Long {
        val moved = if (playing && at > 0) (now - at).coerceAtLeast(0) else 0
        return (positionMs + moved).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
    }
}

/** What a device announces about itself. */
@Serializable
data class DeviceState(
    val id: String,
    val name: String,
    /** "desktop" or "phone". */
    val kind: String,
    /** The music server's address: song ids only mean the same song on the same server. */
    val server: String? = null,
    val playback: PlaybackState? = null,
)

@Serializable
data class ConnectCommand(
    val type: String,
    val positionMs: Long? = null,
    val volume: Int? = null,
    val index: Int? = null,
    /** For [LOAD]: the queue to play, starting at [index] and [positionMs]. */
    val queue: List<ConnectSong>? = null,
    val shuffle: Boolean? = null,
    val repeat: String? = null,
) {
    companion object {
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val SEEK = "seek"
        const val VOLUME = "volume"
        const val JUMP = "jump"
        const val LOAD = "load"
        const val SHUFFLE = "shuffle"
        const val REPEAT = "repeat"
        const val STOP = "stop"
    }
}

data class ConnectDevice(val state: DeviceState, val online: Boolean, val secondsSinceSeen: Long)

data class ReceivedCommand(val seq: Long, val from: String, val command: ConnectCommand)

class ConnectPluginMissingException :
    IntegrationHttpException(
        0,
        "Lidarr: Tonearm Connect isn't installed. Add it under Lidarr → System → Plugins " +
            "(https://github.com/brab-one/Tonearm-Connect) and restart Lidarr.",
    )

/**
 * Talks to the Tonearm Connect plugin in Lidarr, through Lidarr's provider action endpoint
 * (`POST api/v1/notification/action/tonearm?op=…`, the data in the resource's hidden Payload field).
 */
class ConnectClient(private val http: IntegrationHttp, private val json: Json) {
    private val deviceList = ListSerializer(RawDevice.serializer())

    @Serializable
    private data class RawDevice(val id: String = "", val online: Boolean = false, val secondsSinceSeen: Long = 0, val state: String? = null)

    /** The plugin's protocol version. */
    suspend fun hello(config: LidarrConfig, key: String): Int =
        call(config, key, "hello")["protocol"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    suspend fun publish(config: LidarrConfig, key: String, state: DeviceState) {
        call(config, key, "publish", listOf("device" to state.id), json.encodeToString(DeviceState.serializer(), state))
    }

    suspend fun devices(config: LidarrConfig, key: String): List<ConnectDevice> {
        val raw = json.decodeFromJsonElement(deviceList, call(config, key, "devices")["devices"] ?: JsonArray(emptyList()))
        return raw.mapNotNull { device ->
            val state = device.state?.let { runCatching { json.decodeFromString(DeviceState.serializer(), it) }.getOrNull() }
                ?: return@mapNotNull null
            ConnectDevice(state, device.online, device.secondsSinceSeen)
        }
    }

    suspend fun send(config: LidarrConfig, key: String, from: String, target: String, command: ConnectCommand) {
        call(config, key, "send", listOf("device" to from, "target" to target), json.encodeToString(ConnectCommand.serializer(), command))
    }

    /** Commands for [device] after [after], waiting up to [waitSeconds] for one; and the seq to resume from. */
    suspend fun poll(config: LidarrConfig, key: String, device: String, after: Long, waitSeconds: Int): Pair<List<ReceivedCommand>, Long> {
        val response = call(config, key, "poll", listOf("device" to device, "after" to after, "wait" to waitSeconds))
        val commands = (response["commands"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val payload = obj["payload"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val command = runCatching { json.decodeFromString(ConnectCommand.serializer(), payload) }.getOrNull() ?: return@mapNotNull null
            ReceivedCommand(obj["seq"]?.jsonPrimitive?.long ?: 0, obj["from"]?.jsonPrimitive?.contentOrNull.orEmpty(), command)
        }
        return commands to (response["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: after)
    }

    suspend fun forget(config: LidarrConfig, key: String, device: String) {
        call(config, key, "forget", listOf("device" to device))
    }

    private suspend fun call(config: LidarrConfig, key: String, op: String, params: List<Pair<String, Any?>> = emptyList(), payload: String = ""): JsonObject {
        val url = IntegrationHttp.url(config.url, "api/v1/notification/action/tonearm", listOf("op" to op) + params)
        val body = buildJsonObject {
            put("name", JsonPrimitive("Tonearm"))
            put("implementation", JsonPrimitive(IMPLEMENTATION))
            put("configContract", JsonPrimitive("${IMPLEMENTATION}Settings"))
            put("fields", JsonArray(listOf(buildJsonObject { put("name", JsonPrimitive("payload")); put("value", JsonPrimitive(payload)) })))
        }
        val text = try {
            http.postJson(url, body.toString(), config.useServerTls, mapOf("X-Api-Key" to key, "Accept" to "application/json"), service = "Lidarr")
        } catch (e: IntegrationHttpException) {
            // Lidarr can't find the provider type when the plugin isn't there.
            if (e.code == 500 && "targetType" in e.message.orEmpty()) throw ConnectPluginMissingException()
            throw e
        }
        val response = json.parseToJsonElement(text).jsonObject
        response["error"]?.jsonPrimitive?.contentOrNull?.let { throw IntegrationHttpException(0, "Lidarr: Tonearm Connect: $it") }
        return response
    }

    companion object {
        const val IMPLEMENTATION = "TonearmConnect"
        const val PROTOCOL = 1
        /** How long a poll waits on the plugin; below common proxy timeouts (60 s) and OkHttp's read timeout. */
        const val POLL_WAIT_SECONDS = 20
    }
}
