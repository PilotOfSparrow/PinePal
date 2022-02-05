package com.vengefulhedgehog.pinepal.ui.screens.search

import com.vengefulhedgehog.pinepal.domain.model.bluetooth.BleDevice

data class DeviceSearchState(
  val findings: List<BleDevice> = emptyList(),
  val searchInProgress: Boolean = false,
  val locationServiceActive: Boolean = false,
  val locationPermissionGranted: Boolean = false,
  val bluetoothServiceActive: Boolean = false,
)
