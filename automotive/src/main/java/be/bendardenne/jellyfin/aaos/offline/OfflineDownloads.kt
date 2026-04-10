package be.bendardenne.jellyfin.aaos.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import be.bendardenne.jellyfin.aaos.JellyfinAccountManager
import be.bendardenne.jellyfin.aaos.auth
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.Jellyfin
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the ExoPlayer download [SimpleCache], [DownloadManager], and a cache-aware playback
 * [DataSource.Factory] so streamed Jellyfin audio can be stored and played offline.
 */
@Singleton
class OfflineDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jellyfin: Jellyfin,
    private val accountManager: JellyfinAccountManager,
) {

    private val apiClient by lazy { jellyfin.createApi() }

    private val databaseProvider = StandaloneDatabaseProvider(context)

    private val sharedCache: SimpleCache by lazy {
        val dir = File(context.filesDir, "sharkmarmalade_downloads").apply { mkdirs() }
        SimpleCache(dir, NoOpCacheEvictor(), databaseProvider)
    }

    private val upstreamFactory = DataSource.Factory {
        val headers = apiClient.auth(accountManager)
        DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .createDataSource()
    }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            context,
            databaseProvider,
            sharedCache,
            upstreamFactory,
            Runnable::run,
        ).apply {
            maxParallelDownloads = 2
        }
    }

    /** Same disk cache used by downloads, playback, and [androidx.media3.exoplayer.source.preload.DefaultPreloadManager]. */
    fun sharedMediaCache(): SimpleCache = sharedCache

    /** Upstream HTTP factory with Jellyfin auth (refreshed on each [DataSource] creation). */
    fun upstreamHttpDataSourceFactory(): DataSource.Factory = upstreamFactory

    @OptIn(UnstableApi::class)
    fun playbackCacheDataSourceFactory(): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(sharedCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
    }

    @OptIn(UnstableApi::class)
    fun listCompletedMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        downloadManager.downloadIndex.getDownloads().use { downloads ->
            while (downloads.moveToNext()) {
                val download = downloads.download
                if (download.state == Download.STATE_COMPLETED) {
                    mediaItemFromCompletedDownload(download)?.let { items.add(it) }
                }
            }
        }
        return items.sortedBy { it.mediaMetadata.title?.toString().orEmpty() }
    }

    @OptIn(UnstableApi::class)
    fun findCompletedMediaItem(id: String): MediaItem? {
        val download = downloadManager.downloadIndex.getDownload(id) ?: return null
        return mediaItemFromCompletedDownload(download)
    }

    @OptIn(UnstableApi::class)
    fun mediaItemFromCompletedDownload(download: Download): MediaItem? {
        if (download.state != Download.STATE_COMPLETED) {
            return null
        }
        val titleFromData = download.request.data
            .takeIf { it.isNotEmpty() }
            ?.let { String(it, StandardCharsets.UTF_8) }
        val base = download.request.toMediaItem()
        val title = titleFromData?.ifBlank { null } ?: base.mediaMetadata.title ?: download.request.id
        val metadata = base.mediaMetadata.buildUpon()
            .setTitle(title)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return base.buildUpon().setMediaMetadata(metadata).build()
    }
}
