package io.github.deadeyebarb.tonearm.data

import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Favorites toggled during this session, layered over what the server reported when the item
 * was loaded, so every screen (and the notification's heart) agrees right away.
 */
class StarredStore(private val api: SubsonicApi, private val sessions: SessionManager) {
    private val _overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<String, Boolean>> = _overrides

    private fun key(serverId: String, id: String) = "$serverId/$id"

    fun isStarred(serverId: String, id: String, fromServer: Boolean): Boolean =
        _overrides.value[key(serverId, id)] ?: fromServer

    suspend fun set(serverId: String, kind: StarKind, id: String, starred: Boolean) {
        val k = key(serverId, id)
        val previous = _overrides.value[k]
        _overrides.update { it + (k to starred) }
        try {
            api.setStarred(sessions.session(serverId) ?: throw NoServerException(), kind, id, starred)
        } catch (e: Exception) {
            _overrides.update { if (previous == null) it - k else it + (k to previous) }
            throw e
        }
    }
}

/** One-off messages for the snackbar. */
class Messages {
    private val _flow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val flow: kotlinx.coroutines.flow.SharedFlow<String> = _flow

    fun show(text: String) {
        _flow.tryEmit(text)
    }
}
