package com.ngem1.sharkmarmalade

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import com.ngem1.sharkmarmalade.offline.JellyfinDownloadService
import dagger.hilt.android.HiltAndroidApp

@OptIn(UnstableApi::class)
@HiltAndroidApp
class SharkMarmaladeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DownloadService.start(this, JellyfinDownloadService::class.java)
    }
}