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
import java.util.concurrent.TimeUnit

/**
 * Re-downloads offline songs whose file got better on the server: FLAC where the download is an MP3 or
 * AAC (Lidarr upgraded it), or a hi-res FLAC where it was CD quality.
 */
object DownloadUpgrades {
    suspend fun run(context: Context): Int {
        val c = context.container
        val session = c.sessions.active.value ?: c.sessions.awaitActive()
        var upgraded = 0
        for (entry in c.downloads.entries.value.values.filter { it.completed && it.item.serverId == session.id }) {
            val newer = c.versions.better(entry.item, session, force = true) ?: continue
            if (!LibraryVersions.isUpgrade(entry.item.song, newer.song)) continue
            c.downloads.replace(entry, newer)
            upgraded++
        }
        return upgraded
    }
}

class DownloadUpgradeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val n = DownloadUpgrades.run(applicationContext)
        if (n > 0) applicationContext.container.messages.show(if (n == 1) "Upgrading a download to a better file" else "Upgrading $n downloads to better files")
        Result.success()
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
