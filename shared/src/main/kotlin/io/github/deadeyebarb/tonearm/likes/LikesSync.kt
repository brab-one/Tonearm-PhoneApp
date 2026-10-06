package io.github.deadeyebarb.tonearm.likes

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectPluginMissingException
import io.github.deadeyebarb.tonearm.connect.ConnectRoute
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Shares pending likes (YouTube Music songs not in the library yet) between the phone and the desktop
 * through Tonearm Connect's store (on the Tonearm server, or in the plugin in Lidarr). Each side merges and writes back only when the
 * stored version hasn't moved, so a like made on one device appears on the other.
 */
class LikesSync(private val client: ConnectClient, private val json: Json) {
    private val lock = Mutex()

    /** Syncs once; false when Connect can't (the plugin isn't installed, or is too old). */
    suspend fun sync(route: ConnectRoute, pending: PendingLikes): Boolean = lock.withLock {
        repeat(ATTEMPTS) {
            val stored = try {
                client.storeGet(route, STORE_KEY)
            } catch (_: ConnectClient.StoreUnsupportedException) {
                return false
            } catch (_: ConnectPluginMissingException) {
                return false
            }
            val remote = stored.value?.let { runCatching { json.decodeFromString(LikesDocument.serializer(), it) }.getOrNull() } ?: LikesDocument()
            val merged = PendingLikes.merge(pending.document(), remote)
            pending.replace(merged)
            if (merged == remote) return true
            if (client.storePut(route, STORE_KEY, json.encodeToString(LikesDocument.serializer(), merged), stored.version) != null) return true
        }
        true
    }

    companion object {
        const val STORE_KEY = "pending-likes"
        private const val ATTEMPTS = 4
    }
}
