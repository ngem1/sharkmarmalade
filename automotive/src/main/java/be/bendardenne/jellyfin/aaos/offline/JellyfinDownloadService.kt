package be.bendardenne.jellyfin.aaos.offline

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import be.bendardenne.jellyfin.aaos.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that runs Media3 [DownloadManager] work in the background, following
 * [offline downloads](https://developer.android.com/media/media3/exoplayer/downloading-media).
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class JellyfinDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_notification_channel_name,
    R.string.download_notification_channel_description,
) {

    @Inject
    lateinit var offlineDownloads: OfflineDownloads

    private val downloadNotificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(applicationContext, CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager {
        return offlineDownloads.downloadManager
    }

    override fun getScheduler(): Scheduler? {
        return PlatformScheduler(this, JOB_ID)
    }

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        return downloadNotificationHelper.buildProgressNotification(
            this,
            R.mipmap.ic_launcher,
            null,
            null,
            downloads,
            notMetRequirements,
        )
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val JOB_ID = 1001
        const val CHANNEL_ID = "sharkmarmalade_downloads"
    }
}
