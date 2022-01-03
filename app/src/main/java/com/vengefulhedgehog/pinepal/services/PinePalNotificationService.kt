package com.vengefulhedgehog.pinepal.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vengefulhedgehog.pinepal.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class PinePalNotificationService : Service() {

  private val scope = CoroutineScope(Dispatchers.Default + Job())

  override fun onBind(intent: Intent?): IBinder? {
    (application as App).notification
      .onEach { (text, body) ->

      }
      .launchIn(scope)

    return null
  }

  override fun onUnbind(intent: Intent?): Boolean {
    scope.cancel()

    return super.onUnbind(intent)
  }
}
