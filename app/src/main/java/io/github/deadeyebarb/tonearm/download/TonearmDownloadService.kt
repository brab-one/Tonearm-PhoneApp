package io.github.deadeyebarb.tonearm.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import io.github.deadeyebarb.tonearm.MainActivity
import io.github.deadeyebarb.tonearm.R
import io.github.deadeyebarb.tonearm.container

class TonearmDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadRepository.CHANNEL_ID,
    R.string.download_channel_name,
    0,
) {
    override fun getDownloadManager(): DownloadManager = container.downloads.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remaining = downloads.count { it.state != Download.STATE_COMPLETED }
        return container.downloads.notifications.buildProgressNotification(
            this, R.drawable.ic_stat_download, open,
            if (remaining > 0) "$remaining songs left" else null,
            downloads, notMetRequirements,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val JOB_ID = 1
    }
}
