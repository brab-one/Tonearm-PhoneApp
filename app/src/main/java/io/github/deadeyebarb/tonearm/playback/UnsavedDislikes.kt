package io.github.deadeyebarb.tonearm.playback

import android.util.Log
import io.github.deadeyebarb.tonearm.likes.Dislikes
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.mixes.RadioPolicy
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Songs said "Not this one" to whose dislike couldn't be saved yet: no signal for a moment, or the Tonearm server down.
 * They're kept in [file], so they outlast the service, left out of what's queued meanwhile, and sent again until the
 * server has them: at once on [retry] (back online, the next start), else after 10 s, 20 s, 40 s, then every minute.
 * [scope] runs on the main thread.
 */
class UnsavedDislikes(
    private val file: File,
    private val json: Json,
    private val scope: CoroutineScope,
    /** Saves a dislike; throws when it couldn't. */
    private val send: suspend (QueueSong) -> Unit,
    /** Whether trying again can still help: not once the Tonearm server is known to keep no dislikes. */
    private val worthRetrying: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val songs = mutableListOf<QueueSong>()
    /** Failed tries in a row, for how long to wait before the next. */
    private var failures = 0
    private var job: Job? = null
    /** [file] is only written once what it had is back. */
    private var loaded = false
    private val writing = Mutex()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val saved = withContext(io) {
                runCatching { json.decodeFromString(ListSerializer(SavedItem.serializer()), file.readText()) }.getOrNull()
            }.orEmpty().map { QueueSong(it.serverId, it.song) }
            songs.addAll(0, saved.filter { it !in songs })
            loaded = true
            if (songs.isNotEmpty()) retry()
        }
    }

    /** Whether [song] is one of them, or another copy of one (a YouTube Music song and the library's). */
    operator fun contains(song: Song): Boolean {
        if (songs.isEmpty()) return false
        val key = Dislikes.songKey(song.artistLabel, song.title)
        return songs.any { Dislikes.songKey(it.song.artistLabel, it.song.title) == key }
    }

    /** A dislike that just failed: kept, and tried again in a while. */
    fun add(entry: QueueSong) {
        if (entry !in songs) songs += entry
        failures++
        if (job?.isActive == true) {
            scope.launch { write() }
            return
        }
        job = scope.launch {
            write()
            delay(RadioPolicy.retryDelayMs(failures))
            sendAll()
        }
    }

    /** Sends them now, and again in a while as long as some are left. */
    fun retry() {
        if (songs.isEmpty()) return
        job?.cancel()
        job = scope.launch { sendAll() }
    }

    private suspend fun sendAll() {
        while (songs.isNotEmpty()) {
            for (entry in songs.toList()) {
                val done = try {
                    send(entry)
                    failures = 0
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't save the dislike yet", e)
                    !worthRetrying()
                }
                // Still no way through: the others wait too.
                if (!done) {
                    failures++
                    break
                }
                songs.remove(entry)
            }
            write()
            if (songs.isEmpty()) return
            delay(RadioPolicy.retryDelayMs(failures))
        }
    }

    private suspend fun write() = writing.withLock {
        if (!loaded) return@withLock
        val items = songs.map { SavedItem(it.serverId, it.song) }
        withContext(io) {
            runCatching {
                if (items.isEmpty()) {
                    file.delete()
                } else {
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(json.encodeToString(ListSerializer(SavedItem.serializer()), items))
                    tmp.renameTo(file)
                }
            }
        }
    }

    private companion object {
        const val TAG = "UnsavedDislikes"
    }
}
