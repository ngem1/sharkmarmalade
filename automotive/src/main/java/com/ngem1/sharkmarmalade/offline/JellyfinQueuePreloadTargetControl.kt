package com.ngem1.sharkmarmalade.offline

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

/**
 * [TargetPreloadStatusControl] for Jellyfin queue playback: pre-buffer the next and previous
 * tracks via [DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded] so loading uses the same
 * [androidx.media3.exoplayer.source.MediaSource.Factory] as playback (including Jellyfin auth).
 * Playback still uses a cache-backed data source so streamed bytes can populate the shared cache.
 */
@OptIn(UnstableApi::class)
class JellyfinQueuePreloadTargetControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    @Volatile
    var currentPlayingIndex: Int = 0

    override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
        val delta = index - currentPlayingIndex
        return when {
            delta == 1 ->
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(60_000L)

            delta == -1 ->
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(45_000L)

            abs(delta) == 2 ->
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED

            abs(delta) <= 4 ->
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED

            else ->
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }
}
