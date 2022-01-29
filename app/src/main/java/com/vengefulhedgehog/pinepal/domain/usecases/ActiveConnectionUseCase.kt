package com.vengefulhedgehog.pinepal.domain.usecases

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.bluetooth.connect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveConnectionUseCase @Inject constructor(
  private val previouslyConnectedDeviceUseCase: PreviouslyConnectedDeviceUseCase,
  @ApplicationContext private val context: Context,
) {
  private val _connectedDevice = MutableSharedFlow<BluetoothConnection?>(
    replay = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val connectedDevice = _connectedDevice.asSharedFlow()

  fun connect(device: BluetoothDevice) {
    _connectedDevice.tryEmit(device.connect(context))
  }

  fun disconnect() {
    previouslyConnectedDeviceUseCase.mac = null

    getConnectedDevice()?.disconnect()

    _connectedDevice.tryEmit(null)
  }

  fun getConnectedDevice(): BluetoothConnection? = connectedDevice.replayCache.lastOrNull()
}
