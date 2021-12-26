package com.vengefulhedgehog.pinepal.bluetooth

import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.flow.*
import java.util.*

fun BluetoothDevice.connect(
  context: Context,
): BluetoothConnection {
  return BluetoothConnection(context, this)
}

class BluetoothConnection(
  context: Context,
  val device: BluetoothDevice,
) {

  private val bleCallback = BleCallback()
  private val gatt: BluetoothGatt = device.connectGatt(
    context,
    false,
    bleCallback,
  )

  suspend fun listServices(): List<BluetoothGattService> {
    if (gatt.services.isEmpty()) {
      bleCallback.connectionState
        .filter { it == BleConnectionState.CONNECTED }
        .flatMapLatest {
          gatt.discoverServices()

          bleCallback.servicesDiscoveryFlow.filter { it }
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

  suspend fun enableNotificationsFor(
    characteristic: BluetoothGattCharacteristic,
    notificationsDescriptorUuid: UUID,
  ): Boolean {
    val notifDescriptor = characteristic
      .descriptors
      .find { it.uuid == notificationsDescriptorUuid }
      ?: return false

    notifDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

    bleCallback.writeResponse.resetReplayCache()

    return gatt.writeDescriptor(notifDescriptor) &&
        bleCallback.writeResponse
          .filter { (uuid) -> uuid == notificationsDescriptorUuid }
          .map { (_, succeed) -> succeed }
          .firstOrNull() == true
  }

  suspend fun BluetoothGattCharacteristic.read(): ByteArray? {
    bleCallback.readResponse.resetReplayCache()

    if (!gatt.readCharacteristic(this)) {
      return null
    }

    return bleCallback.readResponse
      .filter { (uuid) -> uuid == this.uuid }
      .map { (_, succeed, value) -> value.takeIf { succeed } }
      .firstOrNull()
  }

  suspend fun BluetoothGattCharacteristic.write(
    bytes: ByteArray,
  ): Boolean {
    bleCallback.writeResponse.resetReplayCache()

    return gatt.writeCharacteristic(this.apply { value = bytes }) &&
        bleCallback.writeResponse
          .filter { (uuid) -> uuid == this.uuid }
          .map { (_, succeed, value) -> succeed && value.contentEquals(bytes) }
          .firstOrNull() == true
  }

  suspend fun BluetoothGattCharacteristic.awaitNotification(
    content: ByteArray,
  ): Boolean {
    return bleCallback.notifications
      .mapNotNull { notifications ->
        content.contentEquals(notifications[this.uuid])
      }
      .filterNotNull()
      .onEach {
        bleCallback.notifications.tryEmit(bleCallback.notifications.value - this.uuid)
      }
      .firstOrNull() == true
  }

  suspend fun BluetoothGattCharacteristic.awaitNotification(
    startsWith: Int,
  ): Boolean {
    return bleCallback.notifications
      .mapNotNull { notifications ->
        notifications[this.uuid]?.firstOrNull() == startsWith.toByte()
      }
      .filterNotNull()
      .onEach {
        bleCallback.notifications.tryEmit(bleCallback.notifications.value - this.uuid)
      }
      .firstOrNull() == true
  }

  companion object {
    private const val TAG = "BLE_CONNECTION"
  }
}
