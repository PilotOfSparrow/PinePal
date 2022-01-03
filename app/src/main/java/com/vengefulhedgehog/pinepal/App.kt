package com.vengefulhedgehog.pinepal

import android.app.Application
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

class App : Application() {

  val notification = MutableSharedFlow<Pair<String, String>>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

}
