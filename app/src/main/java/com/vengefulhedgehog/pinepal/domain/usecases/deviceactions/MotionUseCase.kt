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
class MotionUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  @ApplicationScope private val appScope: CoroutineScope,
) {

  private val _stepsCount = MutableStateFlow(0)
  val stepsCount = _stepsCount.asStateFlow()

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
      val subscriptionScope = this

      connection.perform {
        findCharacteristic(UUID_MOTION)
          ?.let { motionChar ->
            motionChar.enableNotifications()

            merge(
              flowOf(motionChar.read()),
              motionChar.observeNotifications(),
            )
          }
          ?.map { motionData ->
            val steps = motionData?.firstOrNull()?.toInt() ?: 0

            steps
          }
          ?.onEach(_stepsCount::emit)
          ?.launchIn(subscriptionScope)
      }
    }
  }

  companion object {
    private val UUID_MOTION = UUID.fromString("00030001-78fc-48fe-8e23-433b3a1942d0")
  }
}
