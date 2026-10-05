package io.github.deadeyebarb.tonearm.integrations

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.deadeyebarb.tonearm.container
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Brainarr's weekly picks, checked every hour: starts the week's run when it's due, collects what it
 * picked into the weekly playlist as it downloads, and deletes last week's (see WeeklyPicks).
 */
class WeeklyPicksWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val (config, key) = c.integrations.requireLidarrOrNull() ?: return Result.success()
        val session = c.sessions.active.value ?: return Result.success()
        return try {
            c.weekly.tick(config, key, session)?.let(c.messages::show)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklyPicksWorker>(1, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("weekly-picks", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Checks right away (after turning weekly picks on, or when Discover opens). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeeklyPicksWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork("weekly-picks-now", ExistingWorkPolicy.KEEP, request)
        }
    }
}
