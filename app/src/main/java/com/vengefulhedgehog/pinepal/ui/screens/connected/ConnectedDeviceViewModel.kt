package com.vengefulhedgehog.pinepal.ui.screens.connected

import android.annotation.SuppressLint
import android.net.Uri
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.domain.handler.SystemEvent
import com.vengefulhedgehog.pinepal.domain.model.bluetooth.DfuProgress
import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.DeviceSearchUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.NotificationsUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.SystemEventsUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.FirmwareUpdateUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.FirmwareVersionUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.TimeSyncUseCase
import com.vengefulhedgehog.pinepal.ui.navigation.AppDirections
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltViewModel
class ConnectedDeviceViewModel @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val timeSyncUseCase: TimeSyncUseCase,
  private val deviceSearchUseCase: DeviceSearchUseCase,
  private val systemEventsUseCase: SystemEventsUseCase,
  private val notificationsUseCase: NotificationsUseCase,
  private val firmwareUpdateUseCase: FirmwareUpdateUseCase,
  private val firmwareVersionUseCase: FirmwareVersionUseCase,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
) : ViewModel() {

  private val _state = MutableStateFlow(ConnectedDeviceState())
  val state = _state.asStateFlow()

  private val postDfuReconnectionRequested = MutableStateFlow(false)

  init {
    upkeepState()
  }

  @SuppressLint("MissingPermission")
  private fun upkeepState() {
    combine(
      activeConnectionUseCase.connectedDevice,
      postDfuReconnectionRequested,
      firmwareVersionUseCase.firmwareVersion,
      notificationsUseCase.hasNotificationsAccess,
    ) { connectedDevice, reconnection, firmwareVersion, hasNotificationsAccess ->
      if (reconnection) {
        state.value.copy(
          reconnection = reconnection
        )
      } else {
        connectedDevice?.let {
          ConnectedDeviceState(
            deviceName = connectedDevice.device.name.orEmpty(),
            deviceAddress = connectedDevice.device.address.orEmpty(),
            firmwareVersion = firmwareVersion ?: "<fetching>",
            notificationAccessGranted = hasNotificationsAccess,
          )
        }
      }
    }
      .combine(firmwareUpdateUseCase.dfuProgress) { state, dfuProgress ->
        state?.copy(
          dfuProgress = dfuProgress,
        )
      }
      .onEach { newState ->
        _state.emit(newState ?: ConnectedDeviceState())

        if (newState?.dfuProgress is DfuProgress.Error) {
          delay(3_000L)
        }
      }
      .flowOn(dispatchers.default)
      .launchIn(viewModelScope)
  }

  fun onBackPress() {
    if (state.value.run { dfuProgress != null || reconnection }) {
      // TODO maybe show something? naahh
    } else {
      systemEventsUseCase.send(SystemEvent.Navigation.Back())
    }
  }

  fun onTimeSync() {
    viewModelScope.launch {
      timeSyncUseCase.sync()
    }
  }

  fun onDeviceDisconnect() {
    activeConnectionUseCase.disconnect()
    systemEventsUseCase.send(
      SystemEvent.Navigation.Back(AppDirections.DEVICE_SEARCH)
    )
  }

  fun onFirmwareSelect(firmwareUri: Uri?) {
    if (firmwareUri == null || ".zip" !in firmwareUri.toString()) {
      // TODO inform user about mistakes of his path (or her, or wtw)
      return
    }

    startFirmwareUpdate(firmwareUri)
  }

  private fun startFirmwareUpdate(firmwareUri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        systemEventsUseCase.send(
          SystemEvent.WindowFlagAdd(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        )

        val connectedDeviceAddress =
          activeConnectionUseCase.connectedDevice.filterNotNull().first().device.address

        firmwareUpdateUseCase.start(firmwareUri)

        activeConnectionUseCase.disconnect()

        deviceSearchUseCase.clearFindings()

        postDfuReconnectionRequested.emit(true)

        deviceSearchUseCase.start()

        val device = withTimeout(40_000L) {
          deviceSearchUseCase.foundDevices
            .first { it.find { it.address == connectedDeviceAddress } != null }
            .first { it.address == connectedDeviceAddress }
        }

        delay(2_000L) // Otherwise device disconnects after 2 sec for some reason

        activeConnectionUseCase.connect(device)

        postDfuReconnectionRequested.emit(false)

        onTimeSync()
      } catch (e: Exception) {
        postDfuReconnectionRequested.emit(false) // TODO do something better
      } finally {
        systemEventsUseCase.send(
          SystemEvent.WindowFlagRemove(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        )
      }
    }
  }
}
