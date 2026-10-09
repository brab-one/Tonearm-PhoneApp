package io.github.deadeyebarb.tonearm.download

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.offline.Download
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.jsonDataStore
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.ArtworkCache
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** What Keep offline keeps track of: the downloads it made, the ones the user took off, and how the last run went. */
@Serializable
data class OfflineState(
    /** Downloads Keep offline added (download ids); every other download is the user's own. */
    val kept: Set<String> = emptySet(),
    /** Kept songs the user removed: left out until they leave the weekly picks and the likes. */
    val skipped: Set<String> = emptySet(),
    val lastRun: Long = 0,
    /** Anything to say about the last run ("12 songs didn't fit"). */
    val result: String? = null,
)

/** What a run does: songs to download, kept downloads to remove, which songs it keeps, and how many didn't fit. */
data class OfflinePlan(
    val add: List<QueueSong>,
    val remove: List<String>,
    val wanted: Set<String>,
    val skipped: Set<String>,
    val leftOut: Int,
)

/** Which songs Keep offline keeps: pure, so it can be tested. */
object OfflinePlanner {
    /** The size of a song whose size and bit rate the server doesn't give. */
    const val UNKNOWN_SIZE = 30L * 1024 * 1024

    /**
     * [sources] in order of priority (the weekly picks, then the liked songs, newest first); null for one that couldn't be
     * read, which keeps everything that's kept (nothing is removed that run). Songs fill [budgetBytes] in that order, each
     * once; YouTube Music songs, files on the phone, disliked and skipped songs are left out. [downloads] are all there
     * are, [failed] the failed ones of them; the user's own (not in [OfflineState.kept]) are never touched or counted.
     * Kept songs of [serverId] nobody wants anymore are removed, except [spared] ones (in the queue, or waiting for
     * their better file to download).
     */
    fun plan(
        sources: List<List<QueueSong>?>,
        serverId: String,
        downloads: Set<String>,
        failed: Set<String>,
        state: OfflineState,
        spared: Set<String>,
        budgetBytes: Long,
        isDisliked: (Song) -> Boolean,
    ): OfflinePlan {
        val wanted = LinkedHashMap<String, QueueSong>()
        val listed = HashSet<String>()
        var used = 0L
        var leftOut = 0
        for (entry in sources.filterNotNull().flatten()) {
            if (YouTubeMusic.isYouTube(entry.serverId) || LocalMusic.isLocal(entry.serverId)) continue
            val id = DownloadRepository.keyOf(entry.serverId, entry.song.id)
            if (!listed.add(id) || id in state.skipped || isDisliked(entry.song)) continue
            // The user's own download: theirs, and not counted.
            if (id in downloads && id !in state.kept) continue
            val size = sizeOf(entry.song)
            // In order: once one doesn't fit, the rest don't either.
            if (leftOut > 0 || used + size > budgetBytes) {
                leftOut++
                continue
            }
            used += size
            wanted[id] = entry
        }
        val complete = sources.none { it == null }
        return OfflinePlan(
            add = wanted.filter { (id, _) -> id !in downloads || id in failed }.values.toList(),
            remove = if (!complete) {
                emptyList()
            } else {
                state.kept.filter { it in downloads && it.startsWith("$serverId/") && it !in wanted && it !in spared }
            },
            wanted = wanted.keys,
            skipped = if (complete) state.skipped.filterTo(HashSet()) { it in listed } else state.skipped,
            leftOut = leftOut,
        )
    }

    /** The song's size, else its length at its bit rate, else [UNKNOWN_SIZE]. */
    fun sizeOf(song: Song): Long = song.size?.takeIf { it > 0 }
        ?: song.duration?.takeIf { it > 0 }?.let { seconds -> song.bitRate?.takeIf { it > 0 }?.let { seconds * it * 125L } }
        ?: UNKNOWN_SIZE
}

/**
 * Keep offline: every night on Wi-Fi while charging, the newest weekly picks and the liked songs (newest first) are
 * downloaded into the space the user gave, and kept songs that left those lists are removed. The user's own downloads are
 * never touched. Kept songs play in the car's Downloads tab like any other download.
 */
class OfflineSync(private val c: AppContainer) {
    private val store = jsonDataStore(c.app, "offline", OfflineState.serializer(), OfflineState(), c.json)
    val state: StateFlow<OfflineState> = store.data.stateIn(c.scope, SharingStarted.Eagerly, OfflineState())
    private val running = Mutex()

    /** The user downloaded these: they're theirs from now on. */
    fun adopt(ids: Collection<String>) = update { s -> if (ids.none { it in s.kept }) s else s.copy(kept = s.kept - ids.toSet()) }

    /** The user removed these: kept ones stay off until they leave the lists. */
    fun skip(ids: Collection<String>) = update { s ->
        val ours = ids.filter { it in s.kept }
        if (ours.isEmpty()) s else s.copy(kept = s.kept - ours.toSet(), skipped = s.skipped + ours)
    }

    /**
     * Every download was removed, kept ones too: Keep offline goes off, or its next night would download them all again.
     * Turning it on again brings them back.
     */
    fun cleared() {
        c.scope.launch {
            var hadKept = false
            store.updateData {
                hadKept = it.kept.isNotEmpty()
                it.copy(kept = emptySet())
            }
            if (!hadKept || !c.settings.current().keepOffline) return@launch
            c.settings.update { it.copy(keepOffline = false) }
            OfflineSyncWorker.schedule(c.app, false)
            c.messages.show("Keep offline is off now, so the kept songs don't come back tonight")
        }
    }

    /**
     * A kept download is being swapped for a better file with another id: the new one is kept too. The old one stays
     * until the new one is downloaded, then the download repository removes it.
     */
    fun renamed(old: String, new: String) = update { s -> if (old in s.kept && new !in s.kept) s.copy(kept = s.kept + new) else s }

    private fun update(change: (OfflineState) -> OfflineState) {
        c.scope.launch { store.updateData(change) }
    }

    /**
     * One run: plans, adds and removes, then waits up to 9 minutes for the downloads (or until [stopped]). Downloads that
     * didn't finish are held until the next run, so they don't carry on over mobile data. True when everything's there.
     */
    suspend fun run(stopped: () -> Boolean = { false }): Boolean = running.withLock {
        val session = c.sessions.active.value ?: c.sessions.awaitActive()
        val downloads = withContext(Dispatchers.Main) { c.downloads }
        downloads.awaitListed()
        runCatching { c.dislikes.refresh() }
        val picks = runCatching { picksOf(session) }.onFailure { Log.w(TAG, "Couldn't read the weekly picks", it) }.getOrNull()
        val liked = runCatching { likedOf(session) }.onFailure { Log.w(TAG, "Couldn't read the liked songs", it) }.getOrNull()
        val settings = c.settings.current()
        val before = store.data.first()
        val entries = downloads.entries.value
        val playing = c.queueStore.load()?.items.orEmpty().map { DownloadRepository.keyOf(it.serverId, it.song.id) }.toSet()
        // Old copies stay until their better file is downloaded (see DownloadRepository.replace).
        val replaced = entries.values.filterNot { it.completed }.mapNotNullTo(HashSet()) { it.replaces }
        val plan = OfflinePlanner.plan(
            listOf(picks, liked), session.id, entries.keys, entries.filterValues { it.failed }.keys, before, playing + replaced,
            settings.keepOfflineGb * GB, isDisliked = { c.dislikes.isDisliked(it.artistLabel, it.title) },
        )
        val kept = (before.kept.filter { it in entries } - plan.remove.toSet()) + plan.add.map { DownloadRepository.keyOf(it.serverId, it.song.id) }
        withContext(Dispatchers.Main) {
            plan.remove.forEach(downloads.manager::removeDownload)
            for (item in plan.add) downloads.manager.addDownload(downloads.request(item))
            // Held by the last run: they carry on now.
            for (id in plan.wanted) if (entries[id]?.state == Download.STATE_STOPPED) downloads.manager.setStopReason(id, Download.STOP_REASON_NONE)
        }
        store.updateData { it.copy(kept = kept.toSet(), skipped = plan.skipped) }
        Log.i(TAG, "Keeping ${plan.wanted.size} songs: ${plan.add.size} to download, ${plan.remove.size} removed, ${plan.leftOut} didn't fit")
        saveArtwork(plan.add)
        var unfinished = emptyList<String>()
        try {
            val until = System.currentTimeMillis() + WAIT_MS
            do {
                delay(POLL_MS)
                unfinished = withContext(Dispatchers.Main) { downloads.manager.currentDownloads }
                    .filter { it.request.id in kept && it.state != Download.STATE_COMPLETED && it.state != Download.STATE_FAILED }
                    .map { it.request.id }
            } while (unfinished.isNotEmpty() && !stopped() && System.currentTimeMillis() < until)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                unfinished.forEach { downloads.manager.setStopReason(it, DownloadRepository.STOP_REASON_KEEP_OFFLINE) }
            }
            val result = listOfNotNull(
                "${plural(plan.leftOut, "song")} didn't fit".takeIf { plan.leftOut > 0 },
                "${unfinished.size} still to download".takeIf { unfinished.isNotEmpty() },
                "Couldn't read your weekly picks".takeIf { picks == null },
                "Couldn't read your liked songs".takeIf { liked == null },
            ).joinToString(" · ").ifEmpty { null }
            withContext(NonCancellable) { store.updateData { it.copy(lastRun = System.currentTimeMillis(), result = result) } }
        }
        unfinished.isEmpty()
    }

    /** The newest ready weekly picks of the user's own, all of them. */
    private suspend fun picksOf(session: ServerSession): List<QueueSong> {
        val week = c.weekly.batches(session).filter { it.state.status == WeeklyState.READY }.maxByOrNull { it.state.created } ?: return emptyList()
        return c.api.playlist(week.playlist.id, session).entry.map { QueueSong(session.id, it) }
    }

    /** Liked songs, the newest like first. */
    private suspend fun likedOf(session: ServerSession): List<QueueSong> =
        c.api.starred(session).song.sortedByDescending { song -> song.starred?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } }
            .map { QueueSong(session.id, it) }

    /** Covers for the car, which shows them from its own cache when there's no signal. */
    private suspend fun saveArtwork(items: List<QueueSong>) = withContext(Dispatchers.IO) {
        for ((serverId, cover) in items.mapNotNull { item -> item.song.coverArt?.let { item.serverId to it } }.distinct()) {
            runCatching { ArtworkCache.fetch(c.app, serverId, cover) }
        }
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    companion object {
        private const val TAG = "OfflineSync"
        const val GB = 1024L * 1024 * 1024
        private const val WAIT_MS = 9 * 60_000L
        private const val POLL_MS = 2_000L
    }
}

/** Runs Keep offline every night on Wi-Fi while charging. */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.current().keepOffline) return Result.success()
        return try {
            if (c.offline.run(stopped = { isStopped })) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoServerException) {
            Result.success()
        } catch (e: Exception) {
            Log.w("OfflineSync", "Keep offline failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val NIGHTLY = "keep-offline"
        private const val ONCE = "keep-offline-once"
        private val night = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        /** Every 24 hours while [on]; off cancels it. */
        fun schedule(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!on) {
                work.cancelUniqueWork(NIGHTLY)
                work.cancelUniqueWork(ONCE)
                return
            }
            val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(night)
                // A big first run takes several: each may only run about ten minutes.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            work.enqueueUniquePeriodicWork(NIGHTLY, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One run soon on Wi-Fi, charging or not (Keep offline was just switched on). */
        fun now(context: Context) = once(context, Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())

        /** One run the next time the phone is on Wi-Fi and charging (a new week of picks is ready). */
        fun soon(context: Context) = once(context, night)

        private fun once(context: Context, constraints: Constraints) {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
