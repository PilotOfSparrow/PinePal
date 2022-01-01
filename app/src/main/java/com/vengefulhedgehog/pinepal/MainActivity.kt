package com.vengefulhedgehog.pinepal

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.flow.*
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

  private val state = MutableStateFlow<ViewState>(ViewState.Loading)

  private val foundDevices = MutableStateFlow(emptySet<BluetoothDevice>())
  private val discoveryProgress = MutableStateFlow(false)

  private val locationEnabled = MutableStateFlow(false)
  private val bluetoothEnabled = MutableStateFlow(false)
  private val permissionsGranted = MutableStateFlow(false)

  private val firmwareVersion = MutableStateFlow<String?>(null)
  private val connectedDevice = MutableStateFlow<BluetoothConnection?>(null)

  private val resultLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) {
    checkRequiredPermissions()
  }
  private val activityResultLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    checkRequiredPermissions()
  }

  private val locationBroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      checkRequiredPermissions()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      PinePalTheme {
        Surface(color = Color.Black) {
          val screenState by state.collectAsState()

          when (screenState) {
            ViewState.Loading -> {}
            is ViewState.ConnectedDevice -> {
              ConnectedDevice(screenState as ViewState.ConnectedDevice)
            }
            is ViewState.DevicesDiscovery -> {
              DevicesDiscovery(screenState as ViewState.DevicesDiscovery)
            }

            ViewState.PermissionsRequired,
            ViewState.ServicesRequired.Location,
            ViewState.ServicesRequired.Bluetooth -> {
              DiscoverySetup(
                description = screenState.getDiscoveryDescription(),
                buttonText = screenState.getDiscoveryButtonText(),
                buttonAction = screenState.getDiscoveryButtonAction(),
              )
            }
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()

    registerReceiver(
      locationBroadcastReceiver,
      IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
    )

    observeStateChanges()
  }

  override fun onResume() {
    super.onResume()

    checkRequiredPermissions()
  }

  override fun onStop() {
    unregisterReceiver(locationBroadcastReceiver)

    super.onStop()
  }

  private fun observeStateChanges() {
    val requiredPermissionsAndServices = combine(
      locationEnabled,
      bluetoothEnabled,
      permissionsGranted,
    ) { locationEnabled, bluetoothEnabled, permissionsGranted ->
      when {
        !permissionsGranted -> ViewState.PermissionsRequired
        !locationEnabled -> ViewState.ServicesRequired.Location
        !bluetoothEnabled -> ViewState.ServicesRequired.Bluetooth
        else -> null
      }
    }.flowOn(Dispatchers.Default)

    val discovery = combine(
      foundDevices,
      discoveryProgress,
      requiredPermissionsAndServices,
    ) { devices, discoveryInProgress, permissionsAndServices ->
      if (permissionsAndServices == null) {
        ViewState.DevicesDiscovery(
          devices = devices.toList(),
          discoveryInProgress = discoveryInProgress,
        )
      } else {
        null
      }
    }.flowOn(Dispatchers.Default)

    val connectedDeviceInfo = combine(
      connectedDevice,
      firmwareVersion,
    ) { connectedDevice, firmwareVersion ->
      connectedDevice?.let {
        ViewState.ConnectedDevice(
          name = connectedDevice.device.name.orEmpty(),
          address = connectedDevice.device.address.orEmpty(),
          firmwareVersion = firmwareVersion ?: "<fetching>",
        )
      }
    }.flowOn(Dispatchers.Default)

    combine(
      foundDevices,
      discoveryProgress,
      requiredPermissionsAndServices,
    ) { devices, discoveryInProgress, requiredPermissionsAndServices ->
      requiredPermissionsAndServices == null && devices.isEmpty() && !discoveryInProgress
    }
      .flowOn(Dispatchers.Default)
      .onEach { shouldStartDiscovery ->
        if (shouldStartDiscovery) {
          startBleScan()
        }
      }
      .launchIn(lifecycleScope)

    combine(
      discovery,
      connectedDeviceInfo,
      requiredPermissionsAndServices,
    ) { discovery, connectedDeviceInfo, permissionsAndServices ->
      connectedDeviceInfo
        ?: discovery
        ?: permissionsAndServices
        ?: ViewState.Loading
    }
      .flowOn(Dispatchers.Default)
      .onEach(state::emit)
      .launchIn(lifecycleScope)
  }

  @Composable
  private fun DiscoverySetup(
    description: String,
    buttonText: String,
    buttonAction: () -> Unit,
  ) {
    Column(
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)
    ) {
      Text(
        text = description,
        color = Color.White,
        textAlign = TextAlign.Center,
      )
      Button(
        modifier = Modifier.padding(top = 8.dp),
        onClick = buttonAction
      ) {
        Text(text = buttonText, color = Color.White)
      }
    }
  }

  @Composable
  private fun ConnectedDevice(
    deviceInfo: ViewState.ConnectedDevice,
  ) {
    Column(
      modifier = Modifier
        .padding(start = 16.dp, end = 16.dp)
        .fillMaxWidth()
        .fillMaxHeight()
    ) {
      Text(
        text = deviceInfo.name,
        color = Color.White,
        modifier = Modifier.padding(top = 12.dp)
      )
      Text(
        text = deviceInfo.address,
        color = Color.White,
        modifier = Modifier.padding(top = 8.dp)
      )
      Text(
        text = "Firmware version ${deviceInfo.firmwareVersion}",
        color = Color.White,
        modifier = Modifier.padding(top = 8.dp)
      )
      Button(
        onClick = { requestAction(BleAction.SYNC_TIME) },
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 20.dp)
      ) {
        Text(text = "Sync time", color = Color.White)
      }
      Button(
        onClick = { requestAction(BleAction.START_DFU) },
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.CenterHorizontally)
          .padding(top = 20.dp)
      ) {
        Text(text = "Start Firmware Update", color = Color.White)
      }
    }
  }

  private fun requestAction(action: BleAction) {
    when (action) {
      BleAction.SYNC_TIME -> syncTime(connectedDevice.value!!)
      BleAction.START_DFU -> startDfu(connectedDevice.value!!)
    }
  }

  @Composable
  private fun DevicesDiscovery(discoveryInfo: ViewState.DevicesDiscovery) {
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
        if (discoveryInfo.discoveryInProgress) {
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
              }
          )
        }
      }
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .background(BackgroundDark)
      ) {
        items(discoveryInfo.devices, key = { it.address }) { device ->
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
    if (discoveryProgress.value) return

    getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.startScan(object : ScanCallback() {
        init {
          discoveryProgress.tryEmit(true)
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
          result?.device?.let { device ->
            // TODO Change to timeout
            if ("Infini" in device.name.orEmpty()) {
              getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.bluetoothLeScanner
                ?.stopScan(this)

              discoveryProgress.tryEmit(false)

              foundDevices.tryEmit(
                foundDevices.value + device
              )
            }
          }
        }
      })
  }

  private fun checkRequiredPermissions() {
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

  private fun connectDevice(device: BluetoothDevice) {
    lifecycleScope.launch {
      connectedDevice.emit(device.connect(applicationContext))
    }
  }

  private fun syncTime(connection: BluetoothConnection) {
    lifecycleScope.launch(Dispatchers.Default) {
      connection.apply {
        val timeChar = findCharacteristic(
          UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
        )

        val time = LocalDateTime.now()
        val microseconds = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now()) / 1e6 * 256
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
          .put(microseconds.toInt().toByte())
          .put(0x0001)
          .array()

        timeChar?.write(timeArray)
      }
    }
  }

  private fun fetchFirmwareVersion(connection: BluetoothConnection) {
    lifecycleScope.launch(Dispatchers.Default) {
      val firmwareVersionString = connection.run {
        val firmwareVersionChar = findCharacteristic(
          UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        )

        firmwareVersionChar?.read()?.decodeToString()
      }

      firmwareVersion.emit(firmwareVersionString)
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

  private fun ViewState.getDiscoveryDescription(): String = when (this) {
    ViewState.PermissionsRequired -> "Multiple permissions required for device searching"
    ViewState.ServicesRequired.Location -> "Location not enabled"
    ViewState.ServicesRequired.Bluetooth -> "Bluetooth not enabled"
    else -> throw IllegalStateException("No discovery description available for $this")
  }

  private fun ViewState.getDiscoveryButtonText(): String = when (this) {
    ViewState.PermissionsRequired -> "Grant"

    ViewState.ServicesRequired.Location,
    ViewState.ServicesRequired.Bluetooth -> "Enable"

    else -> throw IllegalStateException("No discovery button text available for $this")
  }

  private fun ViewState.getDiscoveryButtonAction(): () -> Unit = when (this) {
    ViewState.PermissionsRequired -> {
      {
        // TODO Handle "Never ask again" situation
        resultLauncher.launch(
          arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
          )
        )
      }
    }
    ViewState.ServicesRequired.Location -> {
      {
        activityResultLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
      }
    }
    ViewState.ServicesRequired.Bluetooth -> {
      {
        activityResultLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
      }
    }

    else -> throw IllegalStateException("No discovery button action available for $this")
  }

  enum class BleAction {
    SYNC_TIME,
    START_DFU,
  }

  sealed interface ViewState {

    object Loading : ViewState

    object PermissionsRequired : ViewState

    sealed interface ServicesRequired : ViewState {
      object Location : ServicesRequired

      object Bluetooth : ServicesRequired
    }

    data class DevicesDiscovery(
      val devices: List<BluetoothDevice>,
      val discoveryInProgress: Boolean,
    ) : ViewState

    data class ConnectedDevice(
      val name: String,
      val address: String,
      val firmwareVersion: String,
    ) : ViewState
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

  // For real media control you need
  // a Notification Service
  // and then something like this
//    val m = getSystemService(MediaSessionManager::class.java)!!
//    val component = ComponentName(this, NotiService::class.java)
//    val sessions = m.getActiveSessions(component)
//
//    sessions.forEach {
//      Log.d("Sessions", "$it -- " + (it?.metadata?.keySet()?.joinToString()))
//      Log.d("Sessions", "$it -- " + (it?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)))
//    }
//
//    getSystemService(AudioManager::class.java)
//      ?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
//
//    getSystemService(AudioManager::class.java)
//      ?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))

}
