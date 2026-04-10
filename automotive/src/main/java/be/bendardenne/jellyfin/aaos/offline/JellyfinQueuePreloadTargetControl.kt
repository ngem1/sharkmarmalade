package be.bendardenne.jellyfin.aaos.offline

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

/**
 * [TargetPreloadStatusControl] for Jellyfin queue playback: pre-cache the next and previous
 * tracks (and lighter stages for neighbors) into the shared [androidx.media3.datasource.cache.Cache]
 * so [androidx.media3.datasource.cache.CacheDataSource] hits during real playback.
 */
@OptIn(UnstableApi::class)
class JellyfinQueuePreloadTargetControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    @Volatile
    var currentPlayingIndex: Int = 0

    override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
        val delta = index - currentPlayingIndex
        return when {
            delta == 1 ->
                DefaultPreloadManager.PreloadStatus.specifiedRangeCached(60_000L)

            delta == -1 ->
                DefaultPreloadManager.PreloadStatus.specifiedRangeCached(45_000L)

            abs(delta) == 2 ->
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED

            abs(delta) <= 4 ->
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED

            else ->
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }
}
