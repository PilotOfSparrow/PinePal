package com.vengefulhedgehog.pinepal

import android.app.Application
import android.content.Intent
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.domain.notification.PineTimeNotification
import com.vengefulhedgehog.pinepal.services.PineTimeConnectionService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class App : Application() {

  val connectedDevice = MutableStateFlow<BluetoothConnection?>(null)
  val notification = MutableSharedFlow<PineTimeNotification>(
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  fun onDeviceConnected(connection: BluetoothConnection) {
    if (connection == connectedDevice.value) return

    connectedDevice.tryEmit(connection)

    applicationContext.startForegroundService(
      Intent(applicationContext, PineTimeConnectionService::class.java)
    )
  }
}
