package com.vengefulhedgehog.pinepal.ui.screens.connected

import com.vengefulhedgehog.pinepal.domain.model.bluetooth.DfuProgress

data class ConnectedDeviceState(
  val deviceName: String = "",
  val reconnection: Boolean = false,
  val deviceAddress: String = "",
  val dfuProgress: DfuProgress? = null,
  val firmwareVersion: String = "",
  val notificationAccessGranted: Boolean = false,
)
