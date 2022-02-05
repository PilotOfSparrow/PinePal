package com.vengefulhedgehog.pinepal.domain.usecases

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSearchUseCase @Inject constructor(
  @ApplicationContext private val context: Context,
) {

  private val _searchInProgress = MutableStateFlow(false)
  val searchInProgress = _searchInProgress.asStateFlow()

  private val _foundDevices = MutableStateFlow(emptySet<BluetoothDevice>())
  val foundDevices = _foundDevices.asStateFlow()

  private val bleScanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result?.device?.let(::onDeviceFound)
    }
  }

  fun start() {
    if (_searchInProgress.value) return

    context.getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.startScan(bleScanCallback)
      ?.let { _searchInProgress.tryEmit(true) }
  }

  fun stop() {
    if (!_searchInProgress.value) return

    context.getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.stopScan(bleScanCallback)

    _searchInProgress.tryEmit(false)
  }

  fun clearFindings() {
    _foundDevices.tryEmit(emptySet())
  }

  private fun onDeviceFound(device: BluetoothDevice) {
    _foundDevices.tryEmit(
      _foundDevices.value + device
    )
  }
}
