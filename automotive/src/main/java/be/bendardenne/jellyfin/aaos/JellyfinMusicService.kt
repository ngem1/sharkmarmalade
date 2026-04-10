package be.bendardenne.jellyfin.aaos

import android.accounts.AccountManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.PLAYLIST_INDEX_PREF
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.PLAYLIST_TRACK_POSITON_MS_PREF
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.OFFLINE_DOWNLOADS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.ROOT_ID
import be.bendardenne.jellyfin.aaos.offline.JellyfinQueuePreloadTargetControl
import be.bendardenne.jellyfin.aaos.offline.OfflineDownloads
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import dagger.hilt.android.AndroidEntryPoint
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject
import kotlin.math.min

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class JellyfinMusicService : MediaLibraryService() {

    @Inject
    lateinit var jellyfin: Jellyfin

    @Inject
    lateinit var offlineDownloads: OfflineDownloads

    private lateinit var accountManager: JellyfinAccountManager
    private lateinit var jellyfinApi: ApiClient
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var callback: JellyfinMediaLibrarySessionCallback
    private lateinit var preloadManager: DefaultPreloadManager
    private lateinit var preloadTargetControl: JellyfinQueuePreloadTargetControl

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentPlaybackTime: Long = 0
    private var currentTrack: MediaItem? = null

    private lateinit var playbackPoll: Runnable

    /** Fingerprint of queue contents (not current index) to decide full preload reset vs index-only update. */
    private var lastPreloadQueueSignature: String? = null

    private val downloadManagerListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            mediaLibrarySession.notifyChildrenChanged(OFFLINE_DOWNLOADS, Int.MAX_VALUE, null)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                maybeSyncPreloadManager(player)
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                PreferenceManager.getDefaultSharedPreferences(this@JellyfinMusicService).edit {
                    putInt(PLAYLIST_INDEX_PREF, player.currentMediaItemIndex)
                }

                SuspendToFutureAdapter.launchFuture { reportPlayback(player) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_MARKER, "onCreate")

        accountManager = JellyfinAccountManager(AccountManager.get(applicationContext))
        jellyfinApi = jellyfin.createApi()

        preloadTargetControl = JellyfinQueuePreloadTargetControl()
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(offlineDownloads.sharedMediaCache())
            .setUpstreamDataSourceFactory(offlineDownloads.upstreamHttpDataSourceFactory())
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)
        val preloadBuilder = DefaultPreloadManager.Builder(this, preloadTargetControl)
            .setMediaSourceFactory(mediaSourceFactory)

        val player = preloadBuilder.buildExoPlayer(
            ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.DEFAULT, true),
        )
        preloadManager = preloadBuilder.build()

        player.addListener(playerListener)

        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        pollForPlaybackStatus(player)

        callback = JellyfinMediaLibrarySessionCallback(this, accountManager, jellyfinApi, offlineDownloads)

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(CommandButtons.createButtons(player))
            .build()

        offlineDownloads.downloadManager.addListener(downloadManagerListener)

        if (accountManager.isAuthenticated) {
            onLogin()
        }
    }

    private fun maybeSyncPreloadManager(player: Player) {
        if (!accountManager.isAuthenticated || player !is ExoPlayer) {
            return
        }
        val count = player.mediaItemCount
        val signature = buildString {
            append(count)
            append('|')
            for (i in 0 until count) {
                append(player.getMediaItemAt(i).mediaId)
                append(',')
            }
        }
        if (signature == lastPreloadQueueSignature) {
            updatePreloadPlayingIndex(player)
            return
        }
        lastPreloadQueueSignature = signature
        syncPreloadManagerFullQueue(player)
    }

    private fun syncPreloadManagerFullQueue(player: ExoPlayer) {
        preloadManager.reset()
        val n = player.mediaItemCount
        if (n == 0) {
            return
        }
        val limit = min(n, 40)
        for (i in 0 until limit) {
            preloadManager.add(player.getMediaItemAt(i), i)
        }
        val idx = player.currentMediaItemIndex.coerceIn(0, limit - 1)
        preloadTargetControl.currentPlayingIndex = idx
        preloadManager.setCurrentPlayingIndex(idx)
        preloadManager.invalidate()
    }

    private fun updatePreloadPlayingIndex(player: Player) {
        if (player.mediaItemCount == 0) {
            return
        }
        preloadTargetControl.currentPlayingIndex = player.currentMediaItemIndex
        preloadManager.setCurrentPlayingIndex(player.currentMediaItemIndex)
        preloadManager.invalidate()
    }

    private fun pollForPlaybackStatus(player: ExoPlayer) {
        playbackPoll = Runnable {
            if (player.isPlaying) {
                currentPlaybackTime = player.currentPosition
                currentTrack = player.currentMediaItem

                PreferenceManager.getDefaultSharedPreferences(this@JellyfinMusicService).edit {
                    putLong(PLAYLIST_TRACK_POSITON_MS_PREF, currentPlaybackTime)
                }
            }

            handler.postDelayed(playbackPoll, 1000)
        }
        handler.postDelayed(playbackPoll, 1000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        Log.i(LOG_MARKER, "onDestroy")

        offlineDownloads.downloadManager.removeListener(downloadManagerListener)
        mediaLibrarySession.release()
        mediaLibrarySession.player.removeListener(playerListener)
        mediaLibrarySession.player.release()
        preloadManager.release()
        handler.removeCallbacks(playbackPoll)
        super.onDestroy()
    }

    fun onLogin() {
        jellyfinApi.auth(accountManager)

        // Trigger a refresh upon login.
        mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 5, null)

        maybeSyncPreloadManager(mediaLibrarySession.player)
    }

    private suspend fun reportPlayback(player: Player) {
        val exoPlayer = player as ExoPlayer
        if (currentTrack != null) {
            Log.i(LOG_MARKER, "Reporting playback stopped: ${currentPlaybackTime}")
            jellyfinApi.playStateApi.onPlaybackStopped(
                currentTrack!!.mediaId.toUUID(),
                positionTicks = 10000 * currentPlaybackTime
            )
        }

        if (player.currentMediaItem != null) {
            val format = exoPlayer.audioFormat
            val formatString = "${format?.containerMimeType} at ${format?.averageBitrate} bps"

            Log.i(
                LOG_MARKER,
                "Playing $formatString: ${exoPlayer.currentMediaItem?.localConfiguration?.uri}"
            )
            jellyfinApi.playStateApi.onPlaybackStart(
                player.currentMediaItem!!.mediaId.toUUID(),
                canSeek = true
            )
        }
    }
}
