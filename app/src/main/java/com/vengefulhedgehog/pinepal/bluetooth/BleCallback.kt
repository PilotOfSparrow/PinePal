package com.vengefulhedgehog.pinepal.bluetooth

import android.bluetooth.*
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.*

class BleCallback : BluetoothGattCallback() {
    val connectionState = MutableSharedFlow<BleConnectionState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val writeResponse = MutableSharedFlow<Triple<UUID, Boolean, ByteArray>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications = MutableStateFlow<Map<UUID, ByteArray>>(emptyMap())

    val servicesDiscoveryFlow = MutableStateFlow<Boolean>(false)

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

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        writeResponse.tryEmit(
            Triple(
                characteristic.uuid,
                status == BluetoothGatt.GATT_SUCCESS,
                characteristic.value,
            )
        )
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        writeResponse.tryEmit(
            Triple(
                descriptor.uuid,
                status == BluetoothGatt.GATT_SUCCESS,
                descriptor.value,
            )
        )
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        notifications.tryEmit(
            notifications.value + (characteristic.uuid to characteristic.value)
        )
    }

    companion object {
        private const val TAG = "BLE_CALLBACK"
    }
}
