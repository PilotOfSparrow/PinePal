package com.vengefulhedgehog.pinepal

import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vengefulhedgehog.pinepal.domain.usecases.*
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.FirmwareUpdateUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.FirmwareVersionUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.TimeSyncUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
  private val locationUseCase: LocationUseCase,
  private val timeSyncUseCase: TimeSyncUseCase,
  private val bluetoothUseCase: BluetoothUseCase,
  private val notificationsUseCase: NotificationsUseCase,
  private val deviceScanningUseCase: DeviceScanningUseCase,
  private val firmwareUpdateUseCase: FirmwareUpdateUseCase,
  private val firmwareVersionUseCase: FirmwareVersionUseCase,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  private val previouslyConnectedDeviceUseCase: PreviouslyConnectedDeviceUseCase,
) : ViewModel() {

  private val _state = MutableStateFlow<MainActivity.ViewState>(MainActivity.ViewState.Loading)
  val state = _state.asStateFlow()

  private val _firmwareSelectionRequest = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val firmwareSelectionRequest = _firmwareSelectionRequest.asSharedFlow()

  private val _windowFlagAdded = MutableSharedFlow<Int>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val windowFlagAdded = _windowFlagAdded.asSharedFlow()

  private val _windowFlagRemoved = MutableSharedFlow<Int>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val windowFlagRemoved = _windowFlagRemoved.asSharedFlow()

  private val permissionsGranted = MutableStateFlow(false)
  private val postDfuReconnectionRequested = MutableStateFlow(false)

  init {
    observeStateChanges()

    connectOnFirstSearch()
  }

  fun onPermissionCheckRequested() {
    locationUseCase.checkAvailability()
    bluetoothUseCase.checkAvailability()
    notificationsUseCase.checkNotificationsAccess()

    permissionsGranted.tryEmit(
      locationUseCase.isLocationPermissionGranted()
    )
  }

  fun onDeviceScanRequested() {
    deviceScanningUseCase.start()
  }

  fun onDeviceSelected(device: BluetoothDevice) {
    previouslyConnectedDeviceUseCase.mac = device.address

    deviceScanningUseCase.stop()

    viewModelScope.launch {
      activeConnectionUseCase.connect(device)
    }
  }

  fun onDeviceDisconnectionRequested() {
    activeConnectionUseCase.disconnect()
  }

  fun onTimeSyncRequested() {
    viewModelScope.launch {
      timeSyncUseCase.sync()
    }
  }

  fun onDfuRequested() {
    _firmwareSelectionRequest.tryEmit(Unit)
  }

  fun onFirmwareSelected(firmwareUri: Uri?) {
    if (firmwareUri == null || ".zip" !in firmwareUri.toString()) {
      // TODO inform user about mistakes of his path (or her, or wtw)
      return
    }

    startFirmwareUpdate(firmwareUri)
  }

  private fun startFirmwareUpdate(firmwareUri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _windowFlagAdded.emit(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val connectedDeviceAddress =
          activeConnectionUseCase.connectedDevice.filterNotNull().first().device.address

        firmwareUpdateUseCase.start(firmwareUri)

        activeConnectionUseCase.disconnect()
        deviceScanningUseCase.clearFindings()

        postDfuReconnectionRequested.emit(true)

        deviceScanningUseCase.start()
        val device = deviceScanningUseCase.foundDevices
          .first { it.find { it.address == connectedDeviceAddress } != null }
          .first { it.address == connectedDeviceAddress }

        delay(2_000L) // Otherwise device disconnects after 2 sec for some reason

        onDeviceSelected(device)

        postDfuReconnectionRequested.emit(false)

        onTimeSyncRequested()
      } catch (e: Exception) {
        Log.e("Firmware update", "Failed to update firmware", e)
        // TODO show prompt which would recommend to restart the watch before next attempt
      } finally {
        _windowFlagRemoved.emit(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }
  }

  private fun observeStateChanges() {
    val requiredPermissionsAndServices = combine(
      locationUseCase.locationAvailable,
      bluetoothUseCase.bluetoothAvailable,
      permissionsGranted,
    ) { locationEnabled, bluetoothEnabled, permissionsGranted ->
      when {
        !permissionsGranted -> MainActivity.ViewState.PermissionsRequired
        !locationEnabled -> MainActivity.ViewState.ServicesRequired.Location
        !bluetoothEnabled -> MainActivity.ViewState.ServicesRequired.Bluetooth
        else -> null
      }
    }.flowOn(Dispatchers.Default)

    val discovery = combine(
      deviceScanningUseCase.foundDevices,
      deviceScanningUseCase.discoveryInProgress,
      postDfuReconnectionRequested,
      requiredPermissionsAndServices,
    ) { devices, discoveryInProgress, postDfuReconnectionRequested, requiredPermissionsAndServices ->
      if (requiredPermissionsAndServices == null) {
        MainActivity.ViewState.DevicesDiscovery(
          devices = devices.toList(),
          discoveryInProgress = discoveryInProgress,
          postDfuReconnectionAttempt = postDfuReconnectionRequested,
        )
      } else {
        null
      }
    }.flowOn(Dispatchers.Default)

    val connectedDeviceInfo = activeConnectionUseCase.connectedDevice
      .combine(firmwareVersionUseCase.firmwareVersion) { connectedDevice, firmwareVersion ->
        connectedDevice?.let {
          MainActivity.ViewState.ConnectedDevice(
            name = connectedDevice.device.name.orEmpty(),
            address = connectedDevice.device.address.orEmpty(),
            firmwareVersion = firmwareVersion ?: "<fetching>",
          )
        }
      }
      .combine(notificationsUseCase.hasNotificationsAccess) { connectedDevice, notificationsAccessGranted ->
        connectedDevice?.copy(
          notificationAccessGranted = notificationsAccessGranted,
        )
      }
      .combine(firmwareUpdateUseCase.dfuProgress) { connectedDevice, dfuProgress ->
        connectedDevice?.copy(
          dfuProgress = dfuProgress,
        )
      }
      .flowOn(Dispatchers.Default)

    combine(
      deviceScanningUseCase.foundDevices,
      deviceScanningUseCase.discoveryInProgress,
      requiredPermissionsAndServices,
    ) { devices, discoveryInProgress, requiredPermissionsAndServices ->
      requiredPermissionsAndServices == null && devices.isEmpty() && !discoveryInProgress
    }
      .flowOn(Dispatchers.Default)
      .onEach { shouldStartDiscovery ->
        if (shouldStartDiscovery) {
          deviceScanningUseCase.start()
        }
      }
      .launchIn(viewModelScope)

    combine(
      discovery,
      connectedDeviceInfo,
      requiredPermissionsAndServices,
    ) { discovery, connectedDeviceInfo, permissionsAndServices ->
      connectedDeviceInfo
        ?: discovery
        ?: permissionsAndServices
        ?: MainActivity.ViewState.Loading
    }
      .sample(300L)
      .flowOn(Dispatchers.Default)
      .onEach(_state::emit)
      .launchIn(viewModelScope)
  }

  private fun connectOnFirstSearch() {
    deviceScanningUseCase
      .foundDevices
      .filter { devices -> devices.find { it.address == previouslyConnectedDeviceUseCase.mac } != null }
      .take(1)
      .onEach { devices ->
        onDeviceSelected(
          devices.first { it.address == previouslyConnectedDeviceUseCase.mac }
        )
      }
      .launchIn(viewModelScope)
  }
}
