package io.github.deadeyebarb.tonearm

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.deadeyebarb.tonearm.integrations.DailyDiscoveryWorker

class TonearmApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DailyDiscoveryWorker.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

val Context.container: AppContainer get() = (applicationContext as TonearmApp).container
