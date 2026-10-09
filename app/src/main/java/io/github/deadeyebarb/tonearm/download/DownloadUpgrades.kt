package io.github.deadeyebarb.tonearm.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.LibraryVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Re-downloads offline songs whose file got better on the server: FLAC where the download is an MP3 or
 * AAC (Lidarr upgraded it), or a hi-res FLAC where it was CD quality.
 */
object DownloadUpgrades {
    /** How a check went: downloads being upgraded now, and ones that have to wait (see [DownloadRepository.replace]). */
    data class Outcome(val started: Int, val waiting: Int)

    suspend fun run(context: Context): Outcome {
        val c = context.container
        val session = c.sessions.active.value ?: c.sessions.awaitActive()
        // In a process started for the worker this creates the repository, which lists the downloads a moment later.
        val downloads = withContext(Dispatchers.Main) { c.downloads }
        downloads.awaitListed()
        var started = 0
        var waiting = 0
        for (entry in downloads.entries.value.values.filter { it.completed && it.item.serverId == session.id }) {
            val newer = c.versions.better(entry.item, session, force = true) ?: continue
            if (!LibraryVersions.isUpgrade(entry.item.song, newer.song)) continue
            val newId = downloads.key(newer.serverId, newer.song.id)
            // On its way already: the old copy goes once it's there.
            val pending = downloads.entries.value[newId]?.takeIf { newId != entry.id && !it.completed && !it.failed }
            if (pending != null) continue
            if (!withContext(Dispatchers.Main) { downloads.replace(entry, newer) }) {
                waiting++
                continue
            }
            // A song Keep offline keeps stays kept.
            c.offline.renamed(entry.id, newId)
            started++
        }
        return Outcome(started, waiting)
    }

    fun message(n: Int) = if (n == 1) "Upgrading a download to a better file" else "Upgrading $n downloads to better files"
}

class DownloadUpgradeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val outcome = DownloadUpgrades.run(applicationContext)
        if (outcome.started > 0) applicationContext.container.messages.show(DownloadUpgrades.message(outcome.started))
        // Ones that wait for the app (or Wi-Fi) are tried again a little later, and then less and less often.
        if (outcome.waiting > 0) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DownloadUpgradeWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("download-upgrades", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
