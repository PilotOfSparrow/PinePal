package com.vengefulhedgehog.pinepal.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.domain.handler.SystemEvent
import com.vengefulhedgehog.pinepal.domain.model.bluetooth.BleDevice
import com.vengefulhedgehog.pinepal.domain.usecases.*
import com.vengefulhedgehog.pinepal.ui.navigation.AppDirections
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class DeviceSearchViewModel @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val locationUseCase: LocationUseCase,
  private val bluetoothUseCase: BluetoothUseCase,
  private val systemEventsUseCase: SystemEventsUseCase,
  private val deviceSearchUseCase: DeviceSearchUseCase,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  private val previouslyConnectedDeviceUseCase: PreviouslyConnectedDeviceUseCase,
) : ViewModel() {

  private val _state = MutableStateFlow(DeviceSearchState())
  val state = _state.asStateFlow()

  init {
    upkeepState()

    startScan()
    connectOnFirstSearch()
  }

  private fun upkeepState() {
    combine(
      deviceSearchUseCase.foundDevices,
      deviceSearchUseCase.searchInProgress,
      locationUseCase.locationServiceActive,
      locationUseCase.locationPermissionGranted,
      bluetoothUseCase.bluetoothServiceActive,
    ) { devices, searchInProgress, locationActive, locationPermissionGranted, bluetoothActive->
      DeviceSearchState(
        locationServiceActive = locationActive,
        locationPermissionGranted = locationPermissionGranted,
        bluetoothServiceActive = bluetoothActive,
        searchInProgress = searchInProgress,
        findings = devices.map { bluetoothDevice ->
          BleDevice(
            name = bluetoothDevice.name,
            address = bluetoothDevice.address,
          )
        },
      )
    }
      .onEach(_state::emit)
      .flowOn(dispatchers.default)
      .launchIn(viewModelScope)
  }

  private fun startScan() {
    combine(
      locationUseCase.locationServiceActive,
      locationUseCase.locationPermissionGranted,
      bluetoothUseCase.bluetoothServiceActive,
    ) { locationActive, locationPermissionGranted, bluetoothActive ->
      locationActive && locationPermissionGranted && bluetoothActive
    }
      .filter { it }
      .take(1)
      .onEach { onDeviceScanRequest() }
      .launchIn(viewModelScope)
  }

  private fun connectOnFirstSearch() {
    val forcedConnectionAddress = previouslyConnectedDeviceUseCase.mac ?: return
    deviceSearchUseCase.foundDevices
      .mapNotNull { devices -> devices.find { it.address == forcedConnectionAddress } }
      .take(1)
      .onEach { onDeviceSelect(it.address) }
      .flowOn(dispatchers.default)
      .launchIn(viewModelScope)
  }

  fun onLocationPermissionResult() {
    locationUseCase.checkAvailability()
  }

  fun onDeviceScanRequest() {
    deviceSearchUseCase.start()
  }

  fun onDeviceSelect(deviceAddress: String) {
    deviceSearchUseCase.stop()

    connect(deviceAddress)

    systemEventsUseCase.send(
      SystemEvent.Navigation.To(AppDirections.CONNECTED_DEVICE)
    )
  }

  private fun connect(address: String) {
    deviceSearchUseCase.foundDevices
      .map { devices -> devices.find { it.address == address } }
      .take(1)
      .filterNotNull()
      .onEach { device ->
        activeConnectionUseCase.connect(device)
      }
      .flowOn(dispatchers.default)
      .launchIn(viewModelScope)
  }
}
