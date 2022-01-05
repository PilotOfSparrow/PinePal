package com.vengefulhedgehog.pinepal.bluetooth

import android.bluetooth.*
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

class BleCallback : BluetoothGattCallback() {
  val connectionState = MutableSharedFlow<BleConnectionState>(
    replay = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  private val notifications = MutableStateFlow<Map<UUID, ByteArray>>(emptyMap())
  private val readResponses = MutableStateFlow<Map<UUID, ByteArray?>>(emptyMap())
  private val writeResponses = MutableStateFlow<Map<UUID, ByteArray>>(emptyMap())

  private val servicesDiscoveryFlow = MutableStateFlow<Boolean>(false)

  private val scope = CoroutineScope(Dispatchers.Default + Job())

  override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    val newConnectionState = when (newState) {
      BluetoothProfile.STATE_CONNECTED -> BleConnectionState.CONNECTED
      else -> BleConnectionState.DISCONNECTED
    }

    Log.i(TAG, "Connection state changed: $newConnectionState")

    this.connectionState.tryEmit(newConnectionState)
  }

  override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    servicesDiscoveryFlow.tryEmit(status == BluetoothGatt.GATT_SUCCESS)
  }

  override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int
  ) {
    // should check status == BluetoothGatt.GATT_SUCCESS

    Log.i(TAG, "Characteristic read: ${characteristic.uuid}")

    scope.launch {
      readResponses.emit(
        readResponses.firstOrNull().orEmpty() + Pair(characteristic.uuid, characteristic.value)
      )
    }
  }

  override fun onCharacteristicWrite(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int
  ) {
    // should check status == BluetoothGatt.GATT_SUCCESS

    Log.i(TAG, "Characteristic written: ${characteristic.uuid}")

    scope.launch {
      writeResponses.emit(
        writeResponses.firstOrNull().orEmpty() + Pair(characteristic.uuid, characteristic.value)
      )
    }
  }

  override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int
  ) {
    // should check status == BluetoothGatt.GATT_SUCCESS

    Log.i(TAG, "Descriptor written: ${descriptor.uuid}")

    scope.launch {
      writeResponses.emit(
        writeResponses.firstOrNull().orEmpty() + Pair(descriptor.uuid, descriptor.value)
      )
    }
  }

  override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
  ) {
    Log.i(TAG, "Characteristic changed: ${characteristic.uuid}; value: [${characteristic.value.joinToString()}]")

    scope.launch {
      notifications.emit(
        notifications.firstOrNull().orEmpty() + (characteristic.uuid to characteristic.value)
      )
    }
  }

  suspend fun awaitWrite(uuid: UUID): ByteArray = writeResponses
    .map { writeResponses -> writeResponses[uuid] }
    .filterNotNull()
    .onEach {
      writeResponses.emit(writeResponses.value - uuid)
    }
    .first()

  suspend fun awaitRead(uuid: UUID): ByteArray? = readResponses
    .map { readResponses -> readResponses[uuid] }
    .filterNotNull()
    .onEach {
      readResponses.emit(readResponses.value - uuid)
    }
    .firstOrNull()

  suspend fun awaitServiceDiscovered() {
    servicesDiscoveryFlow.filter { discovered -> discovered }.firstOrNull()
  }

  fun observeNotifications(uuid: UUID): Flow<ByteArray> =
    notifications
      .map { collectedNotifcations ->
        collectedNotifcations[uuid]?.also {
          notifications.emit(notifications.value - uuid)
        }
      }
      .filterNotNull()

  companion object {
    private const val TAG = "BleCallback"
  }
}
