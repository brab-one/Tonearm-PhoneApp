package io.github.deadeyebarb.tonearm.download

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import io.github.deadeyebarb.tonearm.container
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

/** What a download is of; [replaces] is the download it's the better file of, removed once this one is there. */
@Serializable
data class DownloadMeta(val serverId: String, val song: io.github.deadeyebarb.tonearm.subsonic.Song, val replaces: String? = null)

data class DownloadEntry(
    val id: String,
    val item: QueueSong,
    val state: Int,
    val percent: Float,
    val bytes: Long,
    val stopReason: Int = Download.STOP_REASON_NONE,
    /** The download this one is the better file of (see [DownloadRepository.replace]). */
    val replaces: String? = null,
) {
    val completed get() = state == Download.STATE_COMPLETED
    val failed get() = state == Download.STATE_FAILED
    val active get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    /** A kept song the last Keep offline run didn't finish: it carries on in the next one. */
    val held get() = state == Download.STATE_STOPPED && stopReason == DownloadRepository.STOP_REASON_KEEP_OFFLINE
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
    private val loaded = CountDownLatch(1)
    private val listed = CompletableDeferred<Unit>()
    private var progressJob: Job? = null
    private val offline get() = context.container.offline

    init {
        engine.isDownloaded = { serverId, songId ->
            loaded.await(2, TimeUnit.SECONDS)
            key(serverId, songId) in completed
        }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                put(download)?.let(::dropReplaced)
                watchProgress()
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                completed -= download.request.id
                _entries.value -= download.request.id
            }
        })
        // Set before downloads may run, so one meant for Wi-Fi doesn't start on mobile data. The settings may not be
        // read yet: then it's Wi-Fi only, the default, until the collector below has them.
        manager.requirements = requirementsOf(settings.state.value.downloadOnWifiOnly)
        // The manager starts paused, and only the download service resumes it. Changes that reach it directly (the
        // app was in the background) would otherwise wait for the next download started from the app.
        manager.resumeDownloads()
        scope.launch(Dispatchers.IO) {
            try {
                val all = mutableListOf<Download>()
                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) all += cursor.download
                }
                withContext(Dispatchers.Main) {
                    // An old copy whose better file finished while the app wasn't running goes now.
                    all.mapNotNull(::put).forEach(::dropReplaced)
                }
            } finally {
                // Even when the index can't be read, nobody waits for it forever.
                listed.complete(Unit)
                loaded.countDown()
            }
        }
        scope.launch(Dispatchers.Main) {
            settings.state.map { it.downloadOnWifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                val requirements = requirementsOf(wifiOnly)
                // Set on the manager directly: starting the service from here could happen in the background.
                if (manager.requirements != requirements) manager.requirements = requirements
            }
        }
    }

    fun key(serverId: String, songId: String) = keyOf(serverId, songId)

    fun stateOf(serverId: String, songId: String): DownloadEntry? = _entries.value[key(serverId, songId)]

    /** Waits (half a minute at most) until the downloads made before are in [entries]; right after creation they aren't yet. */
    suspend fun awaitListed() {
        withTimeoutOrNull(30_000) { listed.await() }
    }

    /** Downloads songs for the user: kept ones (Keep offline's) become theirs too. */
    fun download(items: List<QueueSong>) {
        offline.adopt(items.map { key(it.serverId, it.song.id) })
        add(items)
    }

    private fun add(items: List<QueueSong>) {
        for (item in items) {
            val id = key(item.serverId, item.song.id)
            val existing = _entries.value[id]
            if (existing?.held == true) {
                // Keep offline's, held until its next run: it carries on now.
                send({ DownloadService.sendSetStopReason(context, TonearmDownloadService::class.java, id, Download.STOP_REASON_NONE, false) }) {
                    manager.setStopReason(id, Download.STOP_REASON_NONE)
                }
                continue
            }
            if (existing != null && !existing.failed) continue
            addRequest(request(item))
        }
    }

    private fun addRequest(request: DownloadRequest) =
        send({ DownloadService.sendAddDownload(context, TonearmDownloadService::class.java, request, false) }) { manager.addDownload(request) }

    /**
     * What the download manager gets for [item]: the original file, kept under the song's download key. Tried again
     * after it failed, it still [replaces] the old copy it did.
     */
    fun request(item: QueueSong, replaces: String? = null): DownloadRequest {
        val id = key(item.serverId, item.song.id)
        val meta = DownloadMeta(item.serverId, item.song, replaces ?: _entries.value[id]?.takeIf { it.failed }?.replaces)
        return DownloadRequest.Builder(id, SongUri.build(item.serverId, item.song.id, SongUri.RAW))
            .setCustomCacheKey(engine.downloadKey(item.serverId, item.song.id))
            .setData(json.encodeToString(DownloadMeta.serializer(), meta).encodeToByteArray())
            .build()
    }

    /**
     * Swaps a downloaded song for [newer], the server's better file; false when that has to wait. The old copy stays
     * until the new one is there. Under a new id the new one downloads beside it, and the old one is removed once it's
     * done. Under the same id the two share their bytes, so the old copy goes first: only when the new one downloads
     * straight away, through the download service (which an app in the background can't start) on a network
     * downloads may use. Main thread.
     */
    fun replace(old: DownloadEntry, newer: QueueSong): Boolean {
        val newId = key(newer.serverId, newer.song.id)
        if (newId != old.id) {
            val existing = _entries.value[newId]
            when {
                existing?.completed == true -> delete(listOf(old.id))
                existing == null || existing.failed -> addRequest(request(newer, replaces = old.id))
            }
            return true
        }
        if (manager.requirements.getNotMetRequirements(context) != 0) return false
        try {
            DownloadService.sendRemoveDownload(context, TonearmDownloadService::class.java, old.id, false)
        } catch (_: IllegalStateException) {
            return false
        }
        // Added while it's being removed, the manager downloads it again with the new file once the old bytes are gone,
        // and keeps that in its index.
        addRequest(request(newer))
        return true
    }

    /** Once [entry] is downloaded, the old copy it's the better file of goes. */
    private fun dropReplaced(entry: DownloadEntry) {
        val old = entry.replaces?.let { _entries.value[it] } ?: return
        if (entry.completed && old.state != Download.STATE_REMOVING) delete(listOf(old.id))
    }

    /**
     * Removes downloads for the user, and the better files still on their way for them: Keep offline doesn't bring kept
     * ones back while they're in its lists.
     */
    fun remove(ids: Collection<String>) {
        val upgrades = _entries.value.values.filter { entry -> !entry.completed && entry.replaces?.let { it in ids } == true }.map { it.id }
        offline.skip(ids + upgrades)
        delete(ids + upgrades)
    }

    private fun delete(ids: Collection<String>) {
        for (id in ids) send({ DownloadService.sendRemoveDownload(context, TonearmDownloadService::class.java, id, false) }) { manager.removeDownload(id) }
    }

    fun removeAll() {
        offline.cleared()
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

    private fun put(download: Download): DownloadEntry? {
        val meta = runCatching {
            json.decodeFromString(DownloadMeta.serializer(), download.request.data.decodeToString())
        }.getOrNull() ?: return null
        val id = download.request.id
        if (download.state == Download.STATE_COMPLETED) completed += id else completed -= id
        val entry = DownloadEntry(
            id = id,
            item = QueueSong(meta.serverId, meta.song),
            state = download.state,
            percent = download.percentDownloaded.coerceAtLeast(0f),
            bytes = download.bytesDownloaded,
            stopReason = download.stopReason,
            replaces = meta.replaces,
        )
        _entries.value += id to entry
        return entry
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

        /** A song's download id. */
        fun keyOf(serverId: String, songId: String) = "$serverId/$songId"

        private fun requirementsOf(wifiOnly: Boolean) = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

        /** On kept downloads a Keep offline run couldn't finish: they only carry on in the next run, on Wi-Fi. */
        const val STOP_REASON_KEEP_OFFLINE = 7

        /** By album (title, then which album), then disc and track: the order the Downloads screen lists them in. */
        val ALBUM_ORDER: Comparator<QueueSong> = compareBy(
            { it.song.album?.lowercase() }, { it.serverId }, { it.song.albumId ?: it.song.album.orEmpty() },
            { it.song.discNumber ?: 1 }, { it.song.track ?: 0 }, { it.song.title },
        )
    }
}
