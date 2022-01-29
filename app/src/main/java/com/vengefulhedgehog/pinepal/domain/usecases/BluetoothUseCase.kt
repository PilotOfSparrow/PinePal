package com.vengefulhedgehog.pinepal.domain.usecases

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothUseCase @Inject constructor(
  @ApplicationContext private val context: Context,
) {

  private val _bluetoothAvailable = MutableStateFlow(false)
  val bluetoothAvailable = _bluetoothAvailable.asStateFlow()

  private val bluetoothManager by lazy {
    context.getSystemService(BluetoothManager::class.java)
  }

  private val bluetoothStatusBroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      checkAvailability()
    }
  }

  init {
    context.registerReceiver(
      bluetoothStatusBroadcastReceiver,
      IntentFilter(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
    )
  }

  fun checkAvailability() {
    _bluetoothAvailable.tryEmit(
      bluetoothManager?.adapter?.isEnabled == true
    )
  }
}
