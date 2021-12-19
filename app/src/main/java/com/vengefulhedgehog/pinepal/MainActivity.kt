package com.vengefulhedgehog.pinepal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import com.vengefulhedgehog.pinepal.bluetooth.connect
import com.vengefulhedgehog.pinepal.ui.theme.PinePalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    override fun onStart() {
        super.onStart()

        getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeScanner
            ?.startScan(object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.device?.let {
                        if ("Infini" in it.name.orEmpty()) {
                            getSystemService(BluetoothManager::class.java)
                                ?.adapter
                                ?.bluetoothLeScanner
                                ?.stopScan(this)

                            devices.tryEmit(
                                devices.value + it
                            )
                        }

                    }
                }
            })
    }

    private val datFile: ByteArray by lazy {
        assets
            .open("pinetime-mcuboot-app-image-1.7.1.dat")
            .let {
                (it as AssetManager.AssetInputStream).use { it.readBytes() }
            }
    }

    private val binFile: ByteArray by lazy {
        assets
            .open("pinetime-mcuboot-app-image-1.7.1.bin")
            .let {
                (it as AssetManager.AssetInputStream).use { it.readBytes() }
            }
    }

    private val devices = MutableStateFlow(emptySet<BluetoothDevice>())

    private fun connectDevice(device: BluetoothDevice) {
        lifecycleScope.launch(Dispatchers.Default) {
            device.connect(applicationContext) {
                Log.i("DFU", "Initialization")

                val controlPointCharacteristic =
                    findCharacteristic(UUID.fromString("00001531-1212-efde-1523-785feabcd123"))
                val packetCharacteristic =
                    findCharacteristic(UUID.fromString("00001532-1212-efde-1523-785feabcd123"))

                checkNotNull(controlPointCharacteristic)
                checkNotNull(packetCharacteristic)

                enableNotificationsFor(
                    characteristic = controlPointCharacteristic,
                    notificationsDescriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                )

                Log.i("DFU", "Step 1")

                controlPointCharacteristic.write(byteArrayOf(0x01, 0x04))

                Log.i("DFU", "Step 2")

                val binFileSizeArray = ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(binFile.size)
                    .array()

                packetCharacteristic.write(ByteArray(8) + binFileSizeArray)
                controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x01, 0x01))

                Log.i("DFU", "Step 3")

                controlPointCharacteristic.write(byteArrayOf(0x02, 0x00))

                Log.i("DFU", "Step 4")

                packetCharacteristic.write(datFile)
                controlPointCharacteristic.write(byteArrayOf(0x02, 0x01))
                controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x02, 0x01))

                Log.i("DFU", "Step 5")

                val receiveNotificationInterval = 0x0A
                controlPointCharacteristic.write(
                    byteArrayOf(
                        0x08,
                        receiveNotificationInterval.toByte()
                    )
                )

                Log.i("DFU", "Step 6")

                controlPointCharacteristic.write(byteArrayOf(0x03))

                Log.i("DFU", "Step 7")

                val segmentSize = 20
                val batchSize = segmentSize * receiveNotificationInterval
                for (batchIndex in binFile.indices step batchSize) {
                    Log.i("DFU_UPLOAD", "Sending batch $batchIndex")

                    for (segmentIndex in batchIndex until batchIndex + batchSize step segmentSize) {
                        Log.i("DFU_UPLOAD", "Sending segment from $segmentIndex to ${segmentIndex + segmentSize}")

                        val sendingResult = packetCharacteristic.write(
                            binFile.sliceArray(
                                segmentIndex until (segmentIndex + segmentSize).coerceAtMost(binFile.size)
                            )
                        )

                        Log.i("DFU_UPLOAD", "Sending result: $sendingResult")
                    }
                    controlPointCharacteristic.awaitNotification(startsWith = 0x11)
                }

                controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x03, 0x01))

                Log.i("DFU", "Step 8")

                controlPointCharacteristic.write(byteArrayOf(0x04))
                controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x04, 0x01))

                Log.i("DFU", "Step 9")

                controlPointCharacteristic.write(byteArrayOf(0x05))
            }
        }
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
