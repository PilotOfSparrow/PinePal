package com.vengefulhedgehog.pinepal.domain.usecases.deviceactions

import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.di.annotations.ApplicationScope
import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeartRateUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  @ApplicationScope private val appScope: CoroutineScope,
) {

  private val _heartRate = MutableStateFlow(0)
  val heartRate = _heartRate.asStateFlow()

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
        findCharacteristic(UUID_HEART_RATE)
          ?.let { hrChar ->
            hrChar.enableNotifications()

            merge(
              flowOf(hrChar.read()),
              hrChar.observeNotifications(),
            )
          }
          ?.map { hrByteArray ->
            if (hrByteArray == null || hrByteArray.isEmpty()) {
              0
            } else {
              ByteBuffer.wrap(hrByteArray).short.toInt()
            }
          }
          ?.onEach(_heartRate::emit)
          ?.launchIn(appScope)
      }
    }
  }

  companion object {
    private val UUID_HEART_RATE = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
  }
}
