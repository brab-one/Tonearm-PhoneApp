package io.github.deadeyebarb.tonearm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A typed DataStore that persists a single @Serializable value as JSON. */
fun <T> jsonDataStore(
    context: Context,
    name: String,
    serializer: KSerializer<T>,
    default: T,
    json: Json,
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(serializer, default, json),
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile = { File(context.filesDir, "datastore/$name.json") },
)

private class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
    private val json: Json,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T =
        try {
            json.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (_: SerializationException) {
            defaultValue
        } catch (_: IllegalArgumentException) {
            defaultValue
        }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}
