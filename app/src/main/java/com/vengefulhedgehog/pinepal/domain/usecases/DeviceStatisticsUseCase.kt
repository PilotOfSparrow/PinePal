package com.vengefulhedgehog.pinepal.domain.usecases

import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.di.annotations.ApplicationScope
import com.vengefulhedgehog.pinepal.domain.model.statistics.CurrentStatistics
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.BatteryUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.HeartRateUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.MotionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceStatisticsUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val motionUseCase: MotionUseCase,
  private val batteryUseCase: BatteryUseCase,
  private val heartRateUseCase: HeartRateUseCase,
  @ApplicationScope private val appScope: CoroutineScope,
) {

  private val _currentStatistics = MutableSharedFlow<CurrentStatistics>(
    replay = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val currentStatistics = _currentStatistics.asSharedFlow()

  init {
    observeDeviceInfo()
  }

  private fun observeDeviceInfo() {
    combine(
      motionUseCase.stepsCount,
      batteryUseCase.batteryLevel,
      heartRateUseCase.heartRate,
    ) { steps, batteryLevel, heartRate ->
      CurrentStatistics(
        steps = steps,
        heartRate = heartRate,
        batteryLevel = batteryLevel,
      )
    }
      .sample(300L)
      .onEach(_currentStatistics::emit)
      .flowOn(dispatchers.default)
      .launchIn(appScope)
  }
}
