package com.example.remind

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.example.remind.data.AppDatabase
import com.example.remind.data.AttachmentStore
import com.example.remind.data.Backup
import com.example.remind.data.Repository
import com.example.remind.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RemindApp : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy { Repository(this, AppDatabase.create(this), AttachmentStore(this)) }
    val backup by lazy { Backup(this, repository) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        appScope.launch {
            repository.seedIfNeeded()
            repository.rescheduleDigests()
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
