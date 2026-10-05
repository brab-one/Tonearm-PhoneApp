package io.github.deadeyebarb.tonearm.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

class IntegrationsRepository(context: Context, json: Json, private val scope: CoroutineScope) {
    private val store = jsonDataStore(context, "integrations", Integrations.serializer(), Integrations(), json)

    val state: StateFlow<Integrations> = store.data.stateIn(scope, SharingStarted.Eagerly, Integrations())

    suspend fun current(): Integrations = store.data.first()

    suspend fun update(transform: (Integrations) -> Integrations) {
        store.updateData(transform)
    }
}
