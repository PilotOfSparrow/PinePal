package com.vengefulhedgehog.pinepal.domain.usecases.deviceactions

import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.di.annotations.ApplicationScope
import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  @ApplicationScope private val appScope: CoroutineScope,
) {

  private val _batteryLevel = MutableStateFlow(-1)
  val batteryLevel = _batteryLevel.asStateFlow()

  private var subscriptionJob: Job? = null

  init {
    activeConnectionUseCase.connectedDevice
      .onEach { connection ->
        if (connection != null) {
          subscribe(connection)
        } else {
          subscriptionJob?.cancel()
        }
      }
      .flowOn(dispatchers.default)
      .launchIn(appScope)
  }

  private fun subscribe(connection: BluetoothConnection) {
    subscriptionJob = appScope.launch(dispatchers.default) {
      connection.perform {
        findCharacteristic(UUID_BATTERY_LEVEL)
          ?.let { batteryLevelChar ->
            batteryLevelChar.enableNotifications()

            merge(
              flowOf(batteryLevelChar.read()),
              batteryLevelChar.observeNotifications(),
            )
          }
          ?.map { batteryLevel ->
            batteryLevel
              ?.takeIf(ByteArray::isNotEmpty)
              ?.firstOrNull()
              ?.toInt()
              ?: 0
          }
          ?.onEach(_batteryLevel::emit)
          ?.launchIn(appScope)
      }
    }
  }

  companion object {
    private val UUID_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
  }
}
