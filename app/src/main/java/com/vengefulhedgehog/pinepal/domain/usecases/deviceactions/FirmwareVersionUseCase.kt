package com.vengefulhedgehog.pinepal.domain.usecases.deviceactions

import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.di.annotations.ApplicationScope
import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirmwareVersionUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  @ApplicationScope private val scope: CoroutineScope,
) {

  private val _firmwareVersion = MutableStateFlow<String?>(null)
  val firmwareVersion = _firmwareVersion.asStateFlow()

  init {
    activeConnectionUseCase.connectedDevice
      .onEach { connection ->
        if (connection != null) {
          fetchFirmwareVersion(connection)
        } else {
          _firmwareVersion.emit(null)
        }
      }
      .launchIn(scope)
  }

  private fun fetchFirmwareVersion(connection: BluetoothConnection) {
    scope.launch(dispatchers.default) {
      val firmwareVersionString = connection.performForResult {
        findCharacteristic(UUID_FIRMWARE_VERSION)
          ?.read()
          ?.decodeToString()
      }

      _firmwareVersion.emit(firmwareVersionString)
    }
  }

  companion object {
    private val UUID_FIRMWARE_VERSION = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
  }
}
