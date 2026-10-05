package io.github.deadeyebarb.tonearm.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

class ServerRepository(context: Context, json: Json, scope: CoroutineScope) {
    private val store = jsonDataStore(context, "servers", ServerList.serializer(), ServerList(), json)

    /** Null until the stored list has been read from disk. */
    val state: StateFlow<ServerList?> = store.data.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun load(): ServerList = store.data.first()

    fun find(id: String): ServerConfig? = state.value?.servers?.firstOrNull { it.id == id }

    suspend fun upsert(config: ServerConfig, makeActive: Boolean) {
        store.updateData { list ->
            val servers = if (list.servers.any { it.id == config.id }) {
                list.servers.map { if (it.id == config.id) config else it }
            } else {
                list.servers + config
            }
            list.copy(servers = servers, activeId = if (makeActive || list.activeId == null) config.id else list.activeId)
        }
    }

    suspend fun remove(id: String) {
        store.updateData { list ->
            val servers = list.servers.filterNot { it.id == id }
            list.copy(servers = servers, activeId = if (list.activeId == id) servers.firstOrNull()?.id else list.activeId)
        }
    }

    suspend fun setActive(id: String) {
        store.updateData { it.copy(activeId = id) }
    }
}
