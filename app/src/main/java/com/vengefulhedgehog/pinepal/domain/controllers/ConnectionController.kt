package com.vengefulhedgehog.pinepal.domain.controllers

import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConnectionController {
  private val _connectedDevice = MutableStateFlow<BluetoothConnection?>(null)
  val connectedDevice = _connectedDevice.asStateFlow()

  fun onDeviceConnected(connection: BluetoothConnection) {
    if (connection == _connectedDevice.value) return

    _connectedDevice.tryEmit(connection)
  }

  fun onDisconnected() {
    _connectedDevice.tryEmit(null)
  }
}
