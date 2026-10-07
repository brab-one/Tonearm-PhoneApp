package io.github.deadeyebarb.tonearm.integrations

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.deadeyebarb.tonearm.container
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Refreshes Daily Discovery (and its server playlist) once a day, even if the app isn't opened.
 */
class DailyDiscoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        // Looks the Tonearm server up if that's due: the mix comes from its listening history.
        c.integrations.current()
        if (c.tonearmServer.server.value?.history != true) return Result.success()
        return try {
            c.daily.ensureToday()
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyDiscoveryWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-discovery", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
