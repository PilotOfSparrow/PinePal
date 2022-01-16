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
import android.location.LocationManager
import android.net.Uri
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
import androidx.compose.material.*
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.bluetooth.connect
import com.vengefulhedgehog.pinepal.extensions.unzipAll
import com.vengefulhedgehog.pinepal.ui.theme.BackgroundDark
import com.vengefulhedgehog.pinepal.ui.theme.PinePalTheme
import com.vengefulhedgehog.pinepal.ui.theme.Purple500
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {

  private val state = MutableStateFlow<ViewState>(ViewState.Loading)

  private val foundDevices = MutableStateFlow(emptySet<BluetoothDevice>())
  private val discoveryProgress = MutableStateFlow(false)
  private val postDfuReconnectionRequested = MutableStateFlow(false)

  private val locationEnabled = MutableStateFlow(false)
  private val bluetoothEnabled = MutableStateFlow(false)
  private val permissionsGranted = MutableStateFlow(false)
  private val notificationsAccessGranted = MutableStateFlow(false)

  private val firmwareVersion = MutableStateFlow<String?>(null)
  private val dfuProgress = MutableStateFlow<DfuProgress?>(null)
  private val connectedDevice: StateFlow<BluetoothConnection?>
    get() = (application as App).connectedDevice.asStateFlow()

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
  private val firmwareSelectionLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { fileUri ->
    // check ends with .zip
    onFirmwareSelected(fileUri)
  }

  private val locationBroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      checkRequiredPermissions()
    }
  }

  private val sharedPrefs by lazy {
    applicationContext.getSharedPreferences(KEY_SHARED_PREF, MODE_PRIVATE)
  }

  private val bleScanCallback = object : ScanCallback() {
    private val connectedDeviceMac by lazy {
      sharedPrefs.getString(KEY_CONNECTED_DEVICE_MAC, null)
    }

    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result?.device?.let { device ->
        if (device.address.orEmpty() == connectedDeviceMac) {
          connectDevice(device)
        }

        foundDevices.tryEmit(
          foundDevices.value + device
        )
      }
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
      postDfuReconnectionRequested,
      requiredPermissionsAndServices,
    ) { devices, discoveryInProgress, postDfuReconnectionRequested, requiredPermissionsAndServices ->
      if (requiredPermissionsAndServices == null) {
        ViewState.DevicesDiscovery(
          devices = devices.toList(),
          discoveryInProgress = discoveryInProgress,
          postDfuReconnectionAttempt = postDfuReconnectionRequested,
        )
      } else {
        null
      }
    }.flowOn(Dispatchers.Default)

    val connectedDeviceInfo = connectedDevice
      .onEach { connection ->
        connection?.let { fetchFirmwareVersion(connection) }
      }
      .combine(firmwareVersion) { connectedDevice, firmwareVersion ->
        connectedDevice?.let {
          ViewState.ConnectedDevice(
            name = connectedDevice.device.name.orEmpty(),
            address = connectedDevice.device.address.orEmpty(),
            firmwareVersion = firmwareVersion ?: "<fetching>",
          )
        }
      }
      .combine(notificationsAccessGranted) { connectedDevice, notificationsAccessGranted ->
        connectedDevice?.copy(
          notificationAccessGranted = notificationsAccessGranted,
        )
      }
      .combine(dfuProgress) { connectedDevice, dfuProgress ->
        connectedDevice?.copy(
          dfuProgress = dfuProgress,
        )
      }
      .flowOn(Dispatchers.Default)

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
          bleScanStart()
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
      .sample(300L)
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
        textAlign = TextAlign.Center,
      )
      Button(
        modifier = Modifier.padding(top = 8.dp),
        onClick = buttonAction
      ) {
        Text(text = buttonText)
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
        modifier = Modifier.padding(top = 12.dp)
      )
      Text(
        text = deviceInfo.address,
        modifier = Modifier.padding(top = 8.dp)
      )
      if (deviceInfo.dfuProgress == null) {
        Text(
          text = "Firmware version ${deviceInfo.firmwareVersion}",
          modifier = Modifier.padding(top = 8.dp)
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(text = "Notifications access status")

          if (deviceInfo.notificationAccessGranted) {
            Text(text = "Granted")
          } else {
            Button(
              modifier = Modifier.padding(12.dp),
              onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }) {
              Text(text = "Grant")
            }
          }
        }
        Button(
          onClick = { requestAction(BleAction.SYNC_TIME) },
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
        ) {
          Text(text = "Sync time")
        }
        Button(
          onClick = { requestAction(BleAction.START_DFU) },
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.CenterHorizontally)
            .padding(top = 20.dp)
        ) {
          Text(text = "Start Firmware Update")
        }
      } else {
        if (deviceInfo.dfuProgress is DfuProgress.Step7) {
          Text(
            text = deviceInfo.dfuProgress.run { "$sentBytes / $firmwareSizeInBytes" },
            modifier = Modifier
              .padding(top = 20.dp)
              .align(Alignment.CenterHorizontally)
          )
          LinearProgressIndicator(
            progress = deviceInfo.dfuProgress.run { sentBytes.toFloat() / firmwareSizeInBytes },
            color = Color.Magenta,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 20.dp)
          )
        } else {
          Text(
            text = deviceInfo.dfuProgress.description,
            modifier = Modifier
              .padding(top = 20.dp)
              .align(Alignment.CenterHorizontally)
          )
          LinearProgressIndicator(
            color = Color.Magenta,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 20.dp)
          )
        }
      }
    }
  }

  private fun requestAction(action: BleAction) {
    when (action) {
      BleAction.SYNC_TIME -> syncTime(connectedDevice.value!!)
      BleAction.START_DFU -> firmwareSelectionLauncher.launch(arrayOf("application/zip"))
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
        val text = if (discoveryInfo.postDfuReconnectionAttempt) {
          "Attempting to reconnect"
        } else {
          "Discovering devices"
        }

        Text(
          text = text,
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
          }
        )

        val shouldShowProgress = discoveryInfo.run {
          discoveryInProgress || postDfuReconnectionAttempt
        }
        if (shouldShowProgress) {
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
              .clickable { bleScanStart() }
              .constrainAs(buttonRefresh) {
                top.linkTo(parent.top)
                end.linkTo(parent.end, margin = 16.dp)
                bottom.linkTo(parent.bottom)
              }
          )
        }
      }
      if (!discoveryInfo.postDfuReconnectionAttempt) {
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
                modifier = Modifier.fillMaxWidth()
              )
              Text(text = device.address, color = Color.LightGray, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }

  private fun bleScanStart() {
    if (discoveryProgress.value) return

    getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.startScan(bleScanCallback)
      ?.let { discoveryProgress.tryEmit(true) }
  }

  private fun bleScanStop() {
    if (!discoveryProgress.value) return

    getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.stopScan(bleScanCallback)

    discoveryProgress.tryEmit(false)
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
    notificationsAccessGranted.tryEmit(
      BuildConfig.APPLICATION_ID in notificationsListeners
    )
  }

  private fun connectDevice(device: BluetoothDevice) {
    sharedPrefs.edit {
      putString(KEY_CONNECTED_DEVICE_MAC, device.address)
    }

    bleScanStop()

    lifecycleScope.launch {
      if (postDfuReconnectionRequested.value) {
        delay(10_000L) // Otherwise device disconnects after 2 sec for some reason
      }

      (application as App).onDeviceConnected(device.connect(applicationContext))
    }
  }

  private fun syncTime(connection: BluetoothConnection) {
    lifecycleScope.launch(Dispatchers.Default) {
      connection.perform {
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
      val firmwareVersionString = connection.performForResult {
        val firmwareVersionChar = findCharacteristic(
          UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        )

        firmwareVersionChar?.read()?.decodeToString()
      }

      firmwareVersion.emit(firmwareVersionString)
    }
  }

  private fun onFirmwareSelected(firmwareUri: Uri?) {
    if (firmwareUri == null || ".zip" !in firmwareUri.toString()) {
      // TODO inform user about mistakes of his path (or her, or wtw)
      return
    }

    startFirmwareUpdate(firmwareUri)
  }

  private fun startFirmwareUpdate(firmwareUri: Uri) {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        unzipFirmware(firmwareUri)

        startDfu(connectedDevice.value!!)

        (application as App).connectedDevice.emit(null)

        foundDevices.emit(emptySet())
        firmwareVersion.emit(null)
        postDfuReconnectionRequested.emit(true)

        bleScanStart()

        connectedDevice.filterNotNull().first()

        postDfuReconnectionRequested.emit(false)

        requestAction(BleAction.SYNC_TIME)
      } catch (e: Exception) {
        Log.e("Firmware update", "Failed to update firmware", e)
      }
    }
  }

  private suspend fun unzipFirmware(firmwareUri: Uri) {
    withContext(Dispatchers.IO) {
      val tmpDir = File(cacheDir, FIRMWARE_TMP_FOLDER_NAME)
        .also(File::deleteRecursively) // To make sure we don't have leftovers

      if (!tmpDir.mkdir()) {
        throw IllegalStateException("Can't create tmp folder for firmware")
      }

      contentResolver.openInputStream(firmwareUri)?.use { inputStream ->
        ZipInputStream(inputStream).use { zipStream ->
          zipStream.unzipAll(tmpDir)
        }
      }
    }
  }

  private suspend fun startDfu(connection: BluetoothConnection) {
    withContext(Dispatchers.Default) {
      dfuProgress.emit(DfuProgress.Start)

      connection.perform {
        val tmpFirmwareFolder = cacheDir.listFiles()
          .orEmpty()
          .first { it.name == FIRMWARE_TMP_FOLDER_NAME }

        val firmwareFiles = tmpFirmwareFolder.listFiles()!!

        val fileDat = firmwareFiles.first { ".dat" in it.name }
        val fileBin = firmwareFiles.first { ".bin" in it.name }
        val fileBinSize = fileBin.length()

        val controlPointCharacteristic =
          findCharacteristic(UUID.fromString("00001531-1212-efde-1523-785feabcd123"))
        val packetCharacteristic =
          findCharacteristic(UUID.fromString("00001532-1212-efde-1523-785feabcd123"))

        checkNotNull(packetCharacteristic)
        checkNotNull(controlPointCharacteristic)

        enableNotificationsFor(
          characteristic = controlPointCharacteristic,
          notificationsDescriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        )

        dfuProgress.emit(DfuProgress.Step1)

        controlPointCharacteristic.write(byteArrayOf(0x01, 0x04))

        dfuProgress.emit(DfuProgress.Step2)

        val binFileSizeArray = ByteBuffer
          .allocate(4)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putInt(fileBinSize.toInt())
          .array()

        packetCharacteristic.write(ByteArray(8) + binFileSizeArray)
        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x01, 0x01))

        dfuProgress.emit(DfuProgress.Step3)

        controlPointCharacteristic.write(byteArrayOf(0x02, 0x00))

        dfuProgress.emit(DfuProgress.Step4)

        packetCharacteristic.write(fileDat.readBytes())
        controlPointCharacteristic.write(byteArrayOf(0x02, 0x01))
        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x02, 0x01))

        dfuProgress.emit(DfuProgress.Step5)

        val confirmationNotificationsInterval = 0x64
        controlPointCharacteristic.write(
          byteArrayOf(
            0x08,
            confirmationNotificationsInterval.toByte()
          )
        )

        dfuProgress.emit(DfuProgress.Step6)

        controlPointCharacteristic.write(byteArrayOf(0x03))

        dfuProgress.emit(
          DfuProgress.Step7(
            sentBytes = 0L,
            firmwareSizeInBytes = fileBinSize,
          )
        )

        var sentBytesCount = 0L
        val firmwareSegment = ByteArray(DFU_SEGMENT_SIZE)
        var confirmationCountDown = confirmationNotificationsInterval

        FileInputStream(fileBin).use { fileStream ->
          var segmentBytesCount = fileStream.read(firmwareSegment)
          while (segmentBytesCount > 0) {
            packetCharacteristic.write(
              if (segmentBytesCount == firmwareSegment.size) {
                firmwareSegment
              } else {
                firmwareSegment.copyOfRange(0, segmentBytesCount)
              }
            )

            sentBytesCount += segmentBytesCount

            dfuProgress.emit(
              DfuProgress.Step7(
                sentBytes = sentBytesCount,
                firmwareSizeInBytes = fileBinSize,
              )
            )

            if (sentBytesCount == fileBinSize) break

            if (--confirmationCountDown == 0) {
              confirmationCountDown = confirmationNotificationsInterval

              controlPointCharacteristic.awaitNotification(startsWith = 0x11)
            }

            segmentBytesCount = fileStream.read(firmwareSegment)
          }
        }

        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x03, 0x01))

        dfuProgress.emit(DfuProgress.Step8)

        controlPointCharacteristic.write(byteArrayOf(0x04))
        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x04, 0x01))

        dfuProgress.emit(DfuProgress.Step9)

        try {
          controlPointCharacteristic.write(byteArrayOf(0x05))
        } catch (e: Exception) {
          Log.e("DFU", "Activation timeout") // Sometimes it's respond
        }

        dfuProgress.emit(DfuProgress.Finalization)

        tmpFirmwareFolder.deleteRecursively()

        dfuProgress.emit(null)
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
      val postDfuReconnectionAttempt: Boolean = false,
    ) : ViewState

    data class ConnectedDevice(
      val name: String,
      val address: String,
      val firmwareVersion: String,
      val dfuProgress: DfuProgress? = null,
      val notificationAccessGranted: Boolean = false,
    ) : ViewState
  }

  sealed class DfuProgress(val description: String) {
    object Start : DfuProgress("Starting DFU")

    object Step1 : DfuProgress("Initializing firmware update")

    object Step2 : DfuProgress("Sending firmware size")

    object Step3 : DfuProgress("Preparing to send dat file")

    object Step4 : DfuProgress("Sending dat file")

    object Step5 : DfuProgress("Negotiate confirmation intervals")

    object Step6 : DfuProgress("Preparing to send firmware")

    data class Step7(
      val sentBytes: Long,
      val firmwareSizeInBytes: Long,
    ) : DfuProgress("Sending firmware")

    object Step8 : DfuProgress("Received image validation")

    object Step9 : DfuProgress("Activate new firmware")

    object Finalization : DfuProgress("Finalizing DFU")
  }

  private val Context.notificationsListeners: Set<String>
    get() = NotificationManagerCompat.getEnabledListenerPackages(this.applicationContext)

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

  companion object {
    private const val KEY_SHARED_PREF = "pine_pal_shared_prefs"
    private const val KEY_CONNECTED_DEVICE_MAC = "connected_device_mac"

    private const val FIRMWARE_TMP_FOLDER_NAME = "tmp_firmware"

    private const val DFU_SEGMENT_SIZE = 20
  }

}
