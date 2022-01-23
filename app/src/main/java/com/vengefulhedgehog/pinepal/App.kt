package com.vengefulhedgehog.pinepal

import android.app.Application
import android.content.Intent
import com.vengefulhedgehog.pinepal.domain.media.ActiveMediaInfo
import com.vengefulhedgehog.pinepal.domain.notification.PineTimeNotification
import com.vengefulhedgehog.pinepal.services.PineTimeConnectionService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class App : Application() {

  val activeMediaInfo = MutableStateFlow<ActiveMediaInfo?>(null)
  val notification = MutableSharedFlow<PineTimeNotification>(
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  override fun onCreate() {
    super.onCreate()

    startForegroundService(
      Intent(this, PineTimeConnectionService::class.java)
    )
  }
}
