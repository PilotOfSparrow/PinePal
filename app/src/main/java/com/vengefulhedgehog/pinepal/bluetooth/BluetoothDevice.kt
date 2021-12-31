package com.vengefulhedgehog.pinepal.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context

fun BluetoothDevice.connect(
  context: Context,
): BluetoothConnection {
  return BluetoothConnection(context, this)
}
