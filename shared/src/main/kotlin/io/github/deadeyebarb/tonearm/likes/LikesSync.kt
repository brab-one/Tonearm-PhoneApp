package io.github.deadeyebarb.tonearm.likes

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRoute
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Shares pending likes (YouTube Music songs not in the library yet) between the phone and the desktop
 * through the Tonearm server's store. Each side merges and writes back only when the
 * stored version hasn't moved, so a like made on one device appears on the other.
 */
class LikesSync(private val client: ConnectClient, private val json: Json) {
    private val lock = Mutex()

    /**
     * Syncs once; true when it got through. False when another device kept saving in between: the other side's likes
     * are taken in by then, and this side's go up with the next sync.
     */
    suspend fun sync(route: ConnectRoute, pending: PendingLikes): Boolean = lock.withLock {
        repeat(ATTEMPTS) {
            val stored = client.storeGet(route, STORE_KEY)
            val remote = stored.value?.let { runCatching { json.decodeFromString(LikesDocument.serializer(), it) }.getOrNull() } ?: LikesDocument()
            val merged = pending.mergeFrom(remote)
            if (merged == remote) return true
            if (client.storePut(route, STORE_KEY, json.encodeToString(LikesDocument.serializer(), merged), stored.version) != null) return true
        }
        false
    }

    companion object {
        const val STORE_KEY = "pending-likes"
        private const val ATTEMPTS = 4
    }
}
