package io.github.deadeyebarb.tonearm

import io.github.deadeyebarb.tonearm.download.DownloadUpgradeWorker
import io.github.deadeyebarb.tonearm.download.DownloadUpgrades
import kotlinx.coroutines.launch
import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.deadeyebarb.tonearm.integrations.DailyDiscoveryWorker
import io.github.deadeyebarb.tonearm.integrations.WeeklyPicksWorker

class TonearmApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DailyDiscoveryWorker.schedule(this)
        WeeklyPicksWorker.schedule(this)
        DownloadUpgradeWorker.schedule(this)
        container.likes.start()
        // Downloads whose file got better on the server, checked soon after start too (not only every 12 hours).
        container.scope.launch {
            kotlinx.coroutines.delay(20_000)
            runCatching { DownloadUpgrades.run(this@TonearmApp) }.getOrNull()?.takeIf { it > 0 }?.let { n ->
                container.messages.show(if (n == 1) "Upgrading a download to a better file" else "Upgrading $n downloads to better files")
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

val Context.container: AppContainer get() = (applicationContext as TonearmApp).container
