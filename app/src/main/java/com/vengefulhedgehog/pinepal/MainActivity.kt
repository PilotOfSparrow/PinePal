package com.vengefulhedgehog.pinepal

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.bluetooth.connect
import com.vengefulhedgehog.pinepal.ui.theme.BackgroundDark
import com.vengefulhedgehog.pinepal.ui.theme.PinePalTheme
import com.vengefulhedgehog.pinepal.ui.theme.Purple500
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*

class MainActivity : ComponentActivity() {

  private val datFile: ByteArray by lazy {
    assets
      .open("pinetime-mcuboot-app-image-1.7.1.dat")
      .let {
        (it as AssetManager.AssetInputStream).use(AssetManager.AssetInputStream::readBytes)
      }
  }

  private val binFile: ByteArray by lazy {
    assets
      .open("pinetime-mcuboot-app-image-1.7.1.bin")
      .let {
        (it as AssetManager.AssetInputStream).use(AssetManager.AssetInputStream::readBytes)
      }
  }

  private val devices = MutableStateFlow(emptySet<BluetoothDevice>())
  private val connectedDevice = MutableStateFlow<BluetoothConnection?>(null)
  private val permissionsGranted = MutableStateFlow(false)
  private val bluetoothEnabled = MutableStateFlow(false)
  private val locationEnabled = MutableStateFlow(false)
  private val discoveryInProgress = MutableStateFlow(false)
  private val firmwareVersion = MutableStateFlow<String?>(null)
  private val deviceTime = MutableStateFlow<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val resultLauncher = registerForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
      val fineLocationGiven = results.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)

      permissionsGranted.tryEmit(fineLocationGiven)
    }
    val memesLauncher = registerForActivityResult(
      ActivityResultContracts.StartActivityForResult(),
    ) { results ->
      bluetoothEnabled.tryEmit(results.resultCode == Activity.RESULT_OK)
    }

    setContent {
      PinePalTheme {
        // A surface container using the 'background' color from the theme
        Surface(color = Color.Black) {
          val deviceList by devices.collectAsState()
          val deviceConnection by connectedDevice.collectAsState()
          val permissionsGranted by permissionsGranted.collectAsState()
          val bluetoothEnabled by bluetoothEnabled.collectAsState()
          val locationEnabled by locationEnabled.collectAsState()
          val showDiscoveryProgress by discoveryInProgress.collectAsState()
          val firmwareVesionState by firmwareVersion.collectAsState()

          if (!permissionsGranted) {
            Column(
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
            ) {
              // TODO Handle "Never ask again" situation
              Text(
                text = "Multiple permissions required for device searching",
                color = Color.White,
                textAlign = TextAlign.Center,
              )
              Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                  resultLauncher.launch(
                    arrayOf(
                      Manifest.permission.ACCESS_FINE_LOCATION,
                      Manifest.permission.BLUETOOTH_SCAN,
                      Manifest.permission.BLUETOOTH_CONNECT
                    )
                  )
                }) {
                Text(text = "Grant", color = Color.White)
              }
            }
          } else if (!bluetoothEnabled) {
            Column(
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
            ) {
              // TODO Handle "Never ask again" situation
              Text(
                text = "Bluetooth not enabled",
                color = Color.White,
                textAlign = TextAlign.Center,
              )
              Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                  memesLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }) {
                Text(text = "Enable", color = Color.White)
              }
            }
          } else if (!locationEnabled) {
            Column(
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
            ) {
              // TODO Handle "Never ask again" situation
              Text(
                text = "Location not enabled",
                color = Color.White,
                textAlign = TextAlign.Center,
              )
              Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                  memesLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) {
                Text(text = "Enable", color = Color.White)
              }
            }
          } else if (deviceConnection != null) {
            deviceConnection?.let { connection ->
              lifecycleScope.launch(Dispatchers.Default) {
                val firmwareVersionString = connection.run {
                  val firmwareVersionChar = findCharacteristic(
                    UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
                  )

                  firmwareVersionChar?.read()?.decodeToString()
                }

                firmwareVersion.emit(firmwareVersionString)
              }

              ConnectedDevice(connection, firmwareVesionState)
            }
          } else {
            if (deviceList.isEmpty()) {
              startBleScan()
            }

            BleDevices(devices = deviceList.toList(), discoveryInPorgress = showDiscoveryProgress)
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()

    locationEnabled.tryEmit(
      LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
    )
    bluetoothEnabled.tryEmit(
      getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    )
    permissionsGranted.tryEmit(
      checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    )
  }

  @Composable
  private fun ConnectedDevice(
    connection: BluetoothConnection,
    firmwareVersion: String?,
  ) {
    Column(
      modifier = Modifier
        .padding(start = 16.dp, end = 16.dp)
        .fillMaxWidth()
        .fillMaxHeight()
    ) {
      Text(
        text = connection.device.name,
        color = Color.White,
        modifier = Modifier.padding(top = 12.dp)
      )
      Text(
        text = connection.device.address,
        color = Color.White,
        modifier = Modifier.padding(top = 8.dp)
      )
      Text(
        text = "Firmware version " + (firmwareVersion ?: "..."),
        color = Color.White,
        modifier = Modifier.padding(top = 8.dp)
      )
      Button(
        onClick = { setCurrentTime(connection = connection) },
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 20.dp)
      ) {
        Text(text = "Sync time", color = Color.White)
      }
      Button(
        onClick = { startDfu(connection) },
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.CenterHorizontally)
          .padding(top = 20.dp)
      ) {
        Text(text = "Start Firmware Update", color = Color.White)
      }
    }
  }

  @Composable
  private fun BleDevices(devices: List<BluetoothDevice>, discoveryInPorgress: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
      ConstraintLayout(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 20.dp)
      ) {
        val (title, buttonRefresh) = createRefs()

        Text(
          text = "Discovered devices",
          color = Color.White,
          modifier = Modifier.constrainAs(title) {
            width = Dimension.preferredWrapContent

            linkTo(
              start = parent.start,
              top = parent.top,
              end = buttonRefresh.start,
              bottom = parent.bottom,
              startMargin = 16.dp,
              horizontalBias = 0F,
            )
          })
        if (discoveryInPorgress) {
          CircularProgressIndicator(
            modifier = Modifier.constrainAs(buttonRefresh) {
              top.linkTo(parent.top)
              end.linkTo(parent.end, margin = 16.dp)
              bottom.linkTo(parent.bottom)
            })
        } else {
          Image(
            painter = painterResource(id = android.R.drawable.stat_notify_sync),
            contentDescription = "",
            modifier = Modifier
              .clickable { startBleScan() }
              .constrainAs(buttonRefresh) {
              top.linkTo(parent.top)
              end.linkTo(parent.end, margin = 16.dp)
              bottom.linkTo(parent.bottom)
            })
        }
      }
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .background(BackgroundDark)
      ) {
        items(devices, key = { it.name }) { device ->
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Purple500)
              .padding(20.dp)
              .clickable { connectDevice(device) }
          ) {
            Text(
              text = device.name,
              color = Color.White,
              modifier = Modifier.fillMaxWidth()
            )
            Text(text = device.address, color = Color.LightGray, fontSize = 12.sp)
          }
        }
      }
    }
  }

  private fun startBleScan() {
    if (discoveryInProgress.value) return

    getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.startScan(object : ScanCallback() {
        init {
          discoveryInProgress.tryEmit(true)
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
          result?.device?.let { device ->
            // TODO Change to timeout
            if ("Infini" in device.name.orEmpty()) {
              getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.bluetoothLeScanner
                ?.stopScan(this)

              discoveryInProgress.tryEmit(false)

              devices.tryEmit(
                devices.value + device
              )
            }
          }
        }
      })
  }

  private fun connectDevice(device: BluetoothDevice) {
    lifecycleScope.launch {
      connectedDevice.emit(device.connect(applicationContext))
    }
  }

  private fun setCurrentTime(connection: BluetoothConnection) {
    lifecycleScope.launch(Dispatchers.Default) {
      connection.apply {
        val timeChar = findCharacteristic(
          UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
        )

        val time = LocalDateTime.now()
        val timeArray = ByteBuffer
          .allocate(10)
          .order(ByteOrder.LITTLE_ENDIAN)
          .put((time.year and 0xFF).toByte())
          .put((time.year.shr(8) and 0xFF).toByte())
          .put(time.month.value.toByte())
          .put(time.dayOfMonth.toByte())
          .put(time.hour.toByte())
          .put(time.minute.toByte())
          .put(time.second.toByte())
          .put(time.dayOfWeek.value.toByte())
          .put((ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now()) / 1e6*256).toInt().toByte()) // microseconds
          .put(0x0001)
          .array()

        timeChar?.write(timeArray)
      }
    }
  }

  private fun startDfu(connection: BluetoothConnection) {
    lifecycleScope.launch(Dispatchers.Default) {
      connection.apply {
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
            Log.i(
              "DFU_UPLOAD",
              "Sending segment from $segmentIndex to ${segmentIndex + segmentSize}"
            )

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
