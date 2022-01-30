package com.vengefulhedgehog.pinepal

import android.app.Application
import android.content.Intent
import com.vengefulhedgehog.pinepal.domain.model.media.ActiveMediaInfo
import com.vengefulhedgehog.pinepal.services.PineTimeConnectionService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.MutableStateFlow

@HiltAndroidApp
class App : Application() {

  val activeMediaInfo = MutableStateFlow<ActiveMediaInfo?>(null)

  override fun onCreate() {
    super.onCreate()

    startForegroundService(
      Intent(this, PineTimeConnectionService::class.java)
    )
  }
}
