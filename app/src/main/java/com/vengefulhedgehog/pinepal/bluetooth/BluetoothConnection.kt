package com.vengefulhedgehog.pinepal.bluetooth

import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

class BluetoothConnection(
  context: Context,
  val device: BluetoothDevice,
) {

  private val _state = MutableStateFlow(BleConnectionState.DISCONNECTED)
  val state = _state.asStateFlow()

  private val bleCallback = BleCallback()
  private val gatt: BluetoothGatt = device.connectGatt(
    context,
    false,
    bleCallback,
  )

  private val mutex = Mutex()

  private val scope = CoroutineScope(Dispatchers.Default + Job())

  init {
    bleCallback.connectionState
      .onEach(_state::emit)
      .launchIn(scope)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BluetoothConnection) return false

    if (device != other.device) return false

    return true
  }

  override fun hashCode(): Int {
    return device.hashCode()
  }

  fun disconnect() {
    gatt.disconnect()

    scope.cancel()
  }

  suspend fun listServices(): List<BluetoothGattService> {
    if (gatt.services.isEmpty()) {
      bleCallback.connectionState
        .filter { it == BleConnectionState.CONNECTED }
        .map {
          gatt.discoverServices()

          bleCallback.awaitServiceDiscovered()
        }
        .firstOrNull()
    }

    return gatt.services
  }

  suspend fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
    return listServices()
      .find { service ->
        service.characteristics.find { it.uuid == uuid } != null
      }
      ?.getCharacteristic(uuid)
  }

  fun BluetoothGattCharacteristic.observeNotifications(): Flow<ByteArray> =
    bleCallback
      .observeNotifications(this.uuid)

  suspend fun enableNotificationsFor(
    characteristic: BluetoothGattCharacteristic,
    notificationsDescriptorUuid: UUID,
  ) {
    val notifDescriptor = characteristic
      .descriptors
      .first { it.uuid == notificationsDescriptorUuid }

    gatt.setCharacteristicNotification(characteristic, true)

    notifDescriptor.write(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
  }

  suspend fun BluetoothGattCharacteristic.read(): ByteArray? {
    return mutex.withLock {
      if (gatt.readCharacteristic(this)) {
        bleCallback.awaitRead(uuid)
      } else {
        null
      }
    }
  }

  suspend fun BluetoothGattDescriptor.write(
    bytes: ByteArray,
  ) {
    mutex.withLock {
      Log.i(TAG, "Writing to descriptor: ${this.uuid}")

      val written = gatt.writeDescriptor(this.apply { value = bytes })

      check(written && bleCallback.awaitWrite(uuid).contentEquals(bytes))
    }
  }

  suspend fun BluetoothGattCharacteristic.write(
    bytes: ByteArray,
  ) {
    mutex.withLock {
      Log.i(TAG, "Writing to characteristic: ${this.uuid}")

      val written = gatt.writeCharacteristic(this.apply { value = bytes })

      check(written && bleCallback.awaitWrite(uuid).contentEquals(bytes))
    }
  }

  suspend fun BluetoothGattCharacteristic.awaitNotification(
    expectedContent: ByteArray,
  ): Boolean {
    return bleCallback.observeNotifications(this.uuid)
      .map { notificationContent ->
        notificationContent.contentEquals(expectedContent)
      }
      .take(1)
      .firstOrNull() == true
  }

  suspend fun BluetoothGattCharacteristic.awaitNotification(
    startsWith: Int,
  ): Boolean {
    return bleCallback.observeNotifications(this.uuid)
      .map { notificationContent ->
        notificationContent.firstOrNull() == startsWith.toByte()
      }
      .take(1)
      .firstOrNull() == true
  }

  companion object {
    private const val TAG = "BluetoothConnection"
  }
}
