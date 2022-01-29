package com.vengefulhedgehog.pinepal.domain.usecases

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.bluetooth.connect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveConnectionUseCase @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  private val _connectedDevice = MutableStateFlow<BluetoothConnection?>(null)
  val connectedDevice = _connectedDevice.asStateFlow()

  fun connect(device: BluetoothDevice) {
    _connectedDevice.tryEmit(device.connect(context))
  }

  fun disconnect() {
    connectedDevice.value?.disconnect()

    _connectedDevice.tryEmit(null)
  }
}
