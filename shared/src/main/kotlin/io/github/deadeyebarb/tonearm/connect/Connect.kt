package io.github.deadeyebarb.tonearm.connect

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Where Tonearm Connect runs: the Tonearm server beside the music server, or the plugin in Lidarr. */
sealed interface ConnectRoute {
    /** The Tonearm server, reached at the music server's address with the same login; one hub per user. */
    data class Server(val session: ServerSession) : ConnectRoute

    data class Lidarr(val config: LidarrConfig, val key: String) : ConnectRoute
}

class ConnectUnavailableException :
    IntegrationHttpException(
        0,
        "Tonearm Connect needs the Tonearm server next to your music server (https://github.com/brab-one/Tonearm-Server), " +
            "or Lidarr with its Tonearm Connect plugin.",
    )

/**
 * Talks to Tonearm Connect: the Tonearm server (`<music server>/connect-tonearm/api/<op>`, the data as the
 * request body), or the plugin in Lidarr through Lidarr's provider action endpoint
 * (`POST api/v1/notification/action/tonearm?op=…`, the data in the resource's hidden Payload field).
 * Both answer with the same JSON.
 */
class ConnectClient(private val http: IntegrationHttp, private val json: Json) {
    private val deviceList = ListSerializer(RawDevice.serializer())

    @Serializable
    private data class RawDevice(val id: String = "", val online: Boolean = false, val secondsSinceSeen: Long = 0, val state: String? = null)

    /** The protocol version. */
    suspend fun hello(route: ConnectRoute): Int =
        call(route, "hello")["protocol"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    /**
     * Whether the music server's address has a Tonearm server. False when something else answers there
     * (no such page, or Navidrome's web app); errors from a Tonearm server and network errors are thrown.
     */
    suspend fun findServer(session: ServerSession): Boolean {
        val text = try {
            http.post(session.connectUrl("hello"), "", session.client, service = "Tonearm server")
        } catch (e: IntegrationHttpException) {
            if (e.code in 400..499 && e.code != 401 && e.code != 429) return false
            throw e
        }
        val response = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        return response["server"]?.jsonPrimitive?.contentOrNull == "tonearm"
    }

    suspend fun publish(route: ConnectRoute, state: DeviceState) {
        call(route, "publish", listOf("device" to state.id), json.encodeToString(DeviceState.serializer(), state))
    }

    suspend fun devices(route: ConnectRoute): List<ConnectDevice> {
        val raw = json.decodeFromJsonElement(deviceList, call(route, "devices")["devices"] ?: JsonArray(emptyList()))
        return raw.mapNotNull { device ->
            val state = device.state?.let { runCatching { json.decodeFromString(DeviceState.serializer(), it) }.getOrNull() }
                ?: return@mapNotNull null
            ConnectDevice(state, device.online, device.secondsSinceSeen)
        }
    }

    suspend fun send(route: ConnectRoute, from: String, target: String, command: ConnectCommand) {
        call(route, "send", listOf("device" to from, "target" to target), json.encodeToString(ConnectCommand.serializer(), command))
    }

    /** Commands for [device] after [after], waiting up to [waitSeconds] for one; and the seq to resume from. */
    suspend fun poll(route: ConnectRoute, device: String, after: Long, waitSeconds: Int): Pair<List<ReceivedCommand>, Long> {
        val response = call(route, "poll", listOf("device" to device, "after" to after, "wait" to waitSeconds))
        val commands = (response["commands"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val payload = obj["payload"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val command = runCatching { json.decodeFromString(ConnectCommand.serializer(), payload) }.getOrNull() ?: return@mapNotNull null
            ReceivedCommand(obj["seq"]?.jsonPrimitive?.long ?: 0, obj["from"]?.jsonPrimitive?.contentOrNull.orEmpty(), command)
        }
        return commands to (response["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: after)
    }

    suspend fun forget(route: ConnectRoute, device: String) {
        call(route, "forget", listOf("device" to device))
    }

    /** A shared document from the store (protocol 2): its JSON (null if never saved) and version. */
    data class Stored(val value: String?, val version: Long)

    /** Thrown by [storeGet]/[storePut] when the plugin is older than its store. */
    class StoreUnsupportedException : IntegrationHttpException(0, "Lidarr: Tonearm Connect is too old to share likes; update it under System → Plugins")

    suspend fun storeGet(route: ConnectRoute, name: String): Stored = store {
        val response = call(route, "get", listOf("key" to name))
        Stored(response["value"]?.jsonPrimitive?.contentOrNull, response["version"]?.jsonPrimitive?.long ?: 0)
    }

    /** Saves [value] if the stored version is still [ifVersion]. Null when someone saved in between. */
    suspend fun storePut(route: ConnectRoute, name: String, value: String, ifVersion: Long): Stored? = store {
        val response = call(route, "put", listOf("key" to name, "ifVersion" to ifVersion), value)
        if (response["ok"]?.jsonPrimitive?.contentOrNull == "true") Stored(value, response["version"]?.jsonPrimitive?.long ?: 0) else null
    }

    private suspend fun <T> store(block: suspend () -> T): T = try {
        block()
    } catch (e: IntegrationHttpException) {
        if ("Unknown op" in e.message.orEmpty()) throw StoreUnsupportedException()
        throw e
    }

    private suspend fun call(route: ConnectRoute, op: String, params: List<Pair<String, Any?>> = emptyList(), payload: String = ""): JsonObject {
        val (text, service) = when (route) {
            is ConnectRoute.Server -> http.post(route.session.connectUrl(op, params), payload, route.session.client, service = "Tonearm server") to "Tonearm server"
            is ConnectRoute.Lidarr -> callLidarr(route.config, route.key, op, params, payload) to "Lidarr: Tonearm Connect"
        }
        val response = json.parseToJsonElement(text).jsonObject
        response["error"]?.jsonPrimitive?.contentOrNull?.let { throw IntegrationHttpException(0, "$service: $it") }
        return response
    }

    private suspend fun callLidarr(config: LidarrConfig, key: String, op: String, params: List<Pair<String, Any?>>, payload: String): String {
        val url = IntegrationHttp.url(config.url, "api/v1/notification/action/tonearm", listOf("op" to op) + params)
        val body = buildJsonObject {
            put("name", JsonPrimitive("Tonearm"))
            put("implementation", JsonPrimitive(IMPLEMENTATION))
            put("configContract", JsonPrimitive("${IMPLEMENTATION}Settings"))
            put("fields", JsonArray(listOf(buildJsonObject { put("name", JsonPrimitive("payload")); put("value", JsonPrimitive(payload)) })))
        }
        return try {
            http.postJson(url, body.toString(), config.useServerTls, mapOf("X-Api-Key" to key, "Accept" to "application/json"), service = "Lidarr")
        } catch (e: IntegrationHttpException) {
            // Lidarr can't find the provider type when the plugin isn't there.
            if (e.code == 500 && "targetType" in e.message.orEmpty()) throw ConnectPluginMissingException()
            throw e
        }
    }

    companion object {
        const val IMPLEMENTATION = "TonearmConnect"
        const val PROTOCOL = 1
        /** How long a poll waits; below common proxy timeouts (60 s) and OkHttp's read timeout. */
        const val POLL_WAIT_SECONDS = 20
    }
}

/**
 * Picks where Connect runs. The Tonearm server wins when the music server's address has one (it serves
 * every user of that Navidrome); otherwise the plugin in Lidarr. What was found is kept for a while, so a
 * device doesn't hop between the two while the other devices stay put.
 */
class ConnectRouter(private val client: ConnectClient) {
    private data class Found(val server: String, val present: Boolean, val at: Long)

    private val lock = Mutex()
    @Volatile private var found: Found? = null

    /** [lidarr] is only asked when there's no Tonearm server. */
    suspend fun route(session: ServerSession?, lidarr: suspend () -> Pair<LidarrConfig, String>?): ConnectRoute {
        if (session != null && hasServer(session)) return ConnectRoute.Server(session)
        val (config, key) = lidarr() ?: throw ConnectUnavailableException()
        return ConnectRoute.Lidarr(config, key)
    }

    private suspend fun hasServer(session: ServerSession): Boolean = lock.withLock {
        val server = session.config.baseUrl + "|" + session.config.username
        val now = System.currentTimeMillis()
        found?.takeIf { it.server == server && now - it.at < if (it.present) RECHECK_FOUND_MS else RECHECK_MISSING_MS }?.let { return it.present }
        client.findServer(session).also { found = Found(server, it, now) }
    }

    private companion object {
        const val RECHECK_FOUND_MS = 10 * 60_000L
        const val RECHECK_MISSING_MS = 2 * 60_000L
    }
}
