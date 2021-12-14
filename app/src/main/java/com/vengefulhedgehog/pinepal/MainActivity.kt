package com.vengefulhedgehog.pinepal

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vengefulhedgehog.pinepal.ui.theme.PinePalTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PinePalTheme {
                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {
                    val dev = devices.collectAsState()

                    LazyColumn {
                        items(dev.value.toList(), key = { it.name }) { device ->
                            Row(
                                modifier = Modifier
                                    .background(Color.Blue)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    text = device.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { connectDevice(device) }
                                        .background(Color.Green)
                                        .padding(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val devices = MutableStateFlow(emptySet<BluetoothDevice>())

    private fun connectDevice(device: BluetoothDevice) {
        device.connectGatt(applicationContext, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        runOnUiThread {
                            Toast.makeText(applicationContext, "DISCONNECTED", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            private var updateChar: BluetoothGattCharacteristic? = null
            private var packetChar: BluetoothGattCharacteristic? = null

            private val datFile: ByteArray = assets.open("pinetime-mcuboot-app-image-1.7.1.dat")
                .let {
                    ByteArray(it.available()).also(it::read)
                }
            private val binFile: ByteArray = assets.open("pinetime-mcuboot-app-image-1.7.1.bin")
                .let {
                    ByteArray(it.available()).also(it::read)
                }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    updateChar = gatt.services
                        .find {
                            it.characteristics.find {
                                it.uuid.toString().contains("00001531-1212-efde-1523-785feabcd123")
                            } != null
                        }
                        ?.getCharacteristic(UUID.fromString("00001531-1212-efde-1523-785feabcd123"))

                    packetChar = gatt.services
                        .find {
                            it.characteristics.find {
                                it.uuid.toString().contains("00001532-1212-efde-1523-785feabcd123")
                            } != null
                        }
                        ?.getCharacteristic(UUID.fromString("00001532-1212-efde-1523-785feabcd123"))

                    updateChar?.let {
                        val z = gatt.setCharacteristicNotification(it, true)

                        val descr =
                            it.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descr.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

                        val betta = gatt.writeDescriptor(descr)

                        println(z)
                    }
                } else {
                    println()
                }
            }

            private var first = false
            private var second = false
            private var third = false
            private var thorth = false
            private var fith = false
            private var sixth = false
            private var seventh = false

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
//                    if (!first) {
//                        first = true
//                        updateChar?.let {
//                            gatt.writeCharacteristic(it.apply {
//                                value = byteArrayOf(0x04)
//                            })
//                        }
//                    }
                    if (!second) {
                        second = true
                        packetChar?.let {
                            val zaza = ByteBuffer
                                .allocate(4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(binFile.size)
                                .array()
                            gatt
                                .writeCharacteristic(it.apply {
                                    value = ByteArray(8) + zaza
                                })

                        }
                    } else if (!thorth) {
                        thorth = true
                        gatt.writeCharacteristic(packetChar!!.apply {
                            value = datFile
                        })
//                        val z = gatt.setCharacteristicNotification(updateChar!!, true)
//                        println(z.toString())
                    } else if (!fith) {
                        fith = true
                        gatt.writeCharacteristic(updateChar!!.apply {
                            value = byteArrayOf(0x02, 0x01)
                        })
                    } else if (!sixth && notif) {
                        sixth = true
                        gatt.writeCharacteristic(updateChar!!.apply {
                            value = byteArrayOf(0x03)
                        })
                    } else if (!seventh && sixth && threshold > 0) {

                        val curr = binFile.sliceArray(binPacketIndex until (binPacketIndex + 20).coerceAtMost(binFile.size))
                        binPacketIndex += 20

                        gatt.writeCharacteristic(packetChar!!.apply {
                            value = curr
                        })

                        seventh = binFile.lastIndex <= binPacketIndex
                        threshold--
                    } else {
                        println()
                    }
                }
            }

            private var binPacketIndex = 0
            private var threshold = 10

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
            }


            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.value.contentEquals(byteArrayOf(0x10, 0x01, 0x01))) {
                    if (!third) {
                        third = true
                        gatt.writeCharacteristic(updateChar!!.apply {
                            value = byteArrayOf(0x02, 0x00)
                        })
//                        val z = gatt.setCharacteristicNotification(updateChar!!, true)
//                        println(z.toString())
                    }
                } else if (characteristic.value.contentEquals(byteArrayOf(0x10, 0x02, 0x01))) {
                    gatt.writeCharacteristic(updateChar!!.apply {
                        value = byteArrayOf(0x08, 0x0A)
                    })
                    notif = true
                } else if (characteristic.value.contentEquals(byteArrayOf(0x10, 0x03, 0x01))) {
                    gatt.writeCharacteristic(updateChar!!.apply {
                        value = byteArrayOf(0x04)
                    })
                } else if (characteristic.value.contentEquals(byteArrayOf(0x10, 0x04, 0x01))) {
                    gatt.writeCharacteristic(updateChar!!.apply {
                        value = byteArrayOf(0x05)
                    })
                } else if (characteristic.value.firstOrNull() == 0x11.toByte()) {
                    threshold = 10

                    val curr = binFile.sliceArray(binPacketIndex until (binPacketIndex + 20).coerceAtMost(binFile.size))
                    binPacketIndex += 20

                    gatt.writeCharacteristic(packetChar!!.apply {
                        value = curr
                    })

                    seventh = binFile.lastIndex <= binPacketIndex
                    threshold--
                }
                else {
                    println(characteristic.value)
                }
                println()
            }

            private var notif = false

            override fun onDescriptorRead(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                super.onDescriptorRead(gatt, descriptor, status)
            }

            override fun onServiceChanged(gatt: BluetoothGatt) {
                super.onServiceChanged(gatt)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.writeCharacteristic(updateChar!!.apply {
                        setValue(
                            byteArrayOf(
                                0x01,
                                0x04
                            )
                        )
                    })
                }
            }

            override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
                super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            }

            override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
                super.onPhyRead(gatt, txPhy, rxPhy, status)
            }

            override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
                super.onReliableWriteCompleted(gatt, status)
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
            }
        })
    }

    // To send alert
//    gatt.writeCharacteristic(
//    characteristic
//    .apply {
//        value =  byteArrayOf(
//            0x00.toByte(), // category
//            0x01.toByte(), // amount of notifications
//            0x00.toByte(), // content separator
//        ) +
//                "TESSSST".encodeToByteArray() +
//                byteArrayOf(0x00.toByte()) +
//                "MEMEME".encodeToByteArray()
//    },
//    )

    override fun onStart() {
        super.onStart()

        getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeScanner
            ?.startScan(object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.device?.let {
                        if ("Infini" in it.name) {
                            getSystemService(BluetoothManager::class.java)
                                ?.adapter
                                ?.bluetoothLeScanner
                                ?.stopScan(this)
                        }
                        devices.tryEmit(
                            devices.value + it
                        )
                    }
                }
            })
    }
}

@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name!")
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PinePalTheme {
        Greeting("Android")
    }
}
