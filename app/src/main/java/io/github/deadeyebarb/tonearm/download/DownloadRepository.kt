package io.github.deadeyebarb.tonearm.download

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.media.MediaEngine
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.media.SongUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class DownloadMeta(val serverId: String, val song: io.github.deadeyebarb.tonearm.subsonic.Song)

data class DownloadEntry(
    val id: String,
    val item: QueueSong,
    val state: Int,
    val percent: Float,
    val bytes: Long,
) {
    val completed get() = state == Download.STATE_COMPLETED
    val failed get() = state == Download.STATE_FAILED
    val active get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
}

/**
 * Offline copies of songs, always in original quality. Backed by Media3's DownloadManager,
 * which stores the bytes in [MediaEngine.downloadCache] under [MediaEngine.downloadKey].
 * Create on the main thread.
 */
class DownloadRepository(
    private val context: Context,
    private val engine: MediaEngine,
    private val json: Json,
    settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val manager = DownloadManager(
        context, engine.databaseProvider, engine.downloadCache, engine.subsonicUpstream, Executors.newFixedThreadPool(3),
    ).apply { maxParallelDownloads = 2 }

    val notifications = DownloadNotificationHelper(context, CHANNEL_ID)

    private val _entries = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DownloadEntry>> = _entries

    private val completed = ConcurrentHashMap.newKeySet<String>()
    /** Downloads to start again once their old copy is gone (the same song id with a better file). */
    private val readd = ConcurrentHashMap<String, QueueSong>()
    private val loaded = CountDownLatch(1)
    private val listed = CompletableDeferred<Unit>()
    private var progressJob: Job? = null

    init {
        engine.isDownloaded = { serverId, songId ->
            loaded.await(2, TimeUnit.SECONDS)
            key(serverId, songId) in completed
        }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                put(download)
                watchProgress()
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                completed -= download.request.id
                _entries.value -= download.request.id
                readd.remove(download.request.id)?.let { download(listOf(it)) }
            }
        })
        scope.launch(Dispatchers.IO) {
            try {
                val all = mutableListOf<Download>()
                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) all += cursor.download
                }
                withContext(Dispatchers.Main) { all.forEach(::put) }
            } finally {
                // Even when the index can't be read, nobody waits for it forever.
                listed.complete(Unit)
                loaded.countDown()
            }
        }
        scope.launch(Dispatchers.Main) {
            settings.state.map { it.downloadOnWifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                val requirements = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
                // Set on the manager directly: starting the service from here could happen in the background.
                if (manager.requirements != requirements) manager.requirements = requirements
            }
        }
    }

    fun key(serverId: String, songId: String) = "$serverId/$songId"

    fun stateOf(serverId: String, songId: String): DownloadEntry? = _entries.value[key(serverId, songId)]

    /** Waits (half a minute at most) until the downloads made before are in [entries]; right after creation they aren't yet. */
    suspend fun awaitListed() {
        withTimeoutOrNull(30_000) { listed.await() }
    }

    fun download(items: List<QueueSong>) {
        for (item in items) {
            val id = key(item.serverId, item.song.id)
            val existing = _entries.value[id]
            if (existing != null && !existing.failed) continue
            val request = DownloadRequest.Builder(id, SongUri.build(item.serverId, item.song.id, SongUri.RAW))
                .setCustomCacheKey(engine.downloadKey(item.serverId, item.song.id))
                .setData(json.encodeToString(DownloadMeta.serializer(), DownloadMeta(item.serverId, item.song)).encodeToByteArray())
                .build()
            send({ DownloadService.sendAddDownload(context, TonearmDownloadService::class.java, request, false) }) { manager.addDownload(request) }
        }
    }

    /**
     * Swaps a downloaded song for [newer], the server's better file. Under a new id the new download
     * starts right away; under the same id the old copy has to be removed first (its bytes share the key).
     */
    fun replace(old: DownloadEntry, newer: QueueSong) {
        val newId = key(newer.serverId, newer.song.id)
        if (newId == old.id) {
            readd[old.id] = newer
            remove(listOf(old.id))
        } else {
            download(listOf(newer))
            remove(listOf(old.id))
        }
    }

    fun remove(ids: Collection<String>) {
        for (id in ids) send({ DownloadService.sendRemoveDownload(context, TonearmDownloadService::class.java, id, false) }) { manager.removeDownload(id) }
    }

    fun removeAll() {
        send({ DownloadService.sendRemoveAllDownloads(context, TonearmDownloadService::class.java, false) }) { manager.removeAllDownloads() }
    }

    /**
     * Through the download service, which keeps downloads going in the background. Android doesn't let an
     * app in the background (the upgrade worker) start it: then the manager takes the change [direct]ly,
     * keeps it in its index and carries it out while the app runs. Main thread.
     */
    private fun send(viaService: () -> Unit, direct: () -> Unit) {
        try {
            viaService()
        } catch (_: IllegalStateException) {
            direct()
        }
    }

    fun bytesUsed(): Long = engine.downloadCache.cacheSpace

    private fun put(download: Download) {
        val meta = runCatching {
            json.decodeFromString(DownloadMeta.serializer(), download.request.data.decodeToString())
        }.getOrNull() ?: return
        val id = download.request.id
        if (download.state == Download.STATE_COMPLETED) completed += id else completed -= id
        _entries.value += id to DownloadEntry(
            id = id,
            item = QueueSong(meta.serverId, meta.song),
            state = download.state,
            percent = download.percentDownloaded.coerceAtLeast(0f),
            bytes = download.bytesDownloaded,
        )
    }

    /** DownloadManager only reports state changes, so poll progress while something is downloading. */
    private fun watchProgress() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val current = manager.currentDownloads
                current.forEach(::put)
                if (current.none { it.state == Download.STATE_DOWNLOADING }) break
                delay(750)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"

        /** By album (title, then which album), then disc and track: the order the Downloads screen lists them in. */
        val ALBUM_ORDER: Comparator<QueueSong> = compareBy(
            { it.song.album?.lowercase() }, { it.serverId }, { it.song.albumId ?: it.song.album.orEmpty() },
            { it.song.discNumber ?: 1 }, { it.song.track ?: 0 }, { it.song.title },
        )
    }
}
