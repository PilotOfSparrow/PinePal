package com.vengefulhedgehog.pinepal

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.bluetooth.connect
import com.vengefulhedgehog.pinepal.domain.bluetooth.DfuProgress
import com.vengefulhedgehog.pinepal.extensions.unzipAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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

class MainActivityViewModel : ViewModel() {

  // TODO ya ya, it's bad, it's smells. Not something I'm proud of
  var _applicationContext: Context? = null
    set(value) {
      field = value
      observeStateChanges()
    }
  private val applicationContext get() = _applicationContext!!

  private val _state = MutableStateFlow<MainActivity.ViewState>(MainActivity.ViewState.Loading)
  val state = _state.asStateFlow()

  private val _firmwareSelectionRequest = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val firmwareSelectionRequest = _firmwareSelectionRequest.asSharedFlow()

  private val _windowFlagAdded = MutableSharedFlow<Int>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val windowFlagAdded = _windowFlagAdded.asSharedFlow()

  private val _windowFlagRemoved = MutableSharedFlow<Int>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val windowFlagRemoved = _windowFlagRemoved.asSharedFlow()

  private val connectedDevice: StateFlow<BluetoothConnection?>
    get() = (applicationContext as App).connectedDevice.asStateFlow()

  private val foundDevices = MutableStateFlow(emptySet<BluetoothDevice>())
  private val discoveryProgress = MutableStateFlow(false)
  private val postDfuReconnectionRequested = MutableStateFlow(false)

  private val locationEnabled = MutableStateFlow(false)
  private val bluetoothEnabled = MutableStateFlow(false)
  private val permissionsGranted = MutableStateFlow(false)
  private val notificationsAccessGranted = MutableStateFlow(false)

  private val firmwareVersion = MutableStateFlow<String?>(null)
  private val dfuProgress = MutableStateFlow<DfuProgress?>(null)

  private val sharedPrefs by lazy {
    applicationContext.getSharedPreferences(KEY_SHARED_PREF, ComponentActivity.MODE_PRIVATE)
  }

  private val connectedDeviceMac: String?
    get() = sharedPrefs.getString(KEY_CONNECTED_DEVICE_MAC, null)

  private val bleScanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result
        ?.device
        ?.let(::onDeviceFound)
    }
  }

  init {

  }

  fun requestAction(action: MainActivity.MainActivityScreenAction) {
    when (action) {
      MainActivity.MainActivityScreenAction.SYNC_TIME -> syncTime(connectedDevice.value!!)
      MainActivity.MainActivityScreenAction.START_DFU -> _firmwareSelectionRequest.tryEmit(Unit)
      MainActivity.MainActivityScreenAction.START_BLE_SCAN -> bleScanStart()
      MainActivity.MainActivityScreenAction.CHECK_PERMISSIONS_AND_SERVICES -> checkRequiredPermissionsAndServices()
    }
  }

  private fun bleScanStart() {
    if (discoveryProgress.value) return

    applicationContext.getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.startScan(bleScanCallback)
      ?.let { discoveryProgress.tryEmit(true) }
  }

  private fun bleScanStop() {
    if (!discoveryProgress.value) return

    applicationContext.getSystemService(BluetoothManager::class.java)
      ?.adapter
      ?.bluetoothLeScanner
      ?.stopScan(bleScanCallback)

    discoveryProgress.tryEmit(false)
  }

  fun onDeviceFound(device: BluetoothDevice) {
    if (device.address.orEmpty() == connectedDeviceMac) {
      connectDevice(device)
    }

    foundDevices.tryEmit(
      foundDevices.value + device
    )
  }

  fun connectDevice(device: BluetoothDevice) {
    sharedPrefs.edit {
      putString(KEY_CONNECTED_DEVICE_MAC, device.address)
    }

    bleScanStop()

    viewModelScope.launch {
      if (postDfuReconnectionRequested.value) {
        delay(10_000L) // Otherwise device disconnects after 2 sec for some reason
      }

      (applicationContext as App).onDeviceConnected(device.connect(applicationContext))
    }
  }

  fun onFirmwareSelected(firmwareUri: Uri?) {
    if (firmwareUri == null || ".zip" !in firmwareUri.toString()) {
      // TODO inform user about mistakes of his path (or her, or wtw)
      return
    }

    startFirmwareUpdate(firmwareUri)
  }

  private fun startFirmwareUpdate(firmwareUri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _windowFlagAdded.emit(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        dfuProgress.emit(DfuProgress.Start)

        val firmwareFolder = unzipFirmware(firmwareUri)

        uploadFirmware(
          connection = connectedDevice.value!!,
          firmwareFolder = firmwareFolder,
        )

        firmwareFolder.deleteRecursively()

        dfuProgress.emit(null)

        (applicationContext as App).connectedDevice.emit(null)
        foundDevices.emit(emptySet())
        firmwareVersion.emit(null)
        postDfuReconnectionRequested.emit(true)

        bleScanStart()

        connectedDevice.filterNotNull().first()

        postDfuReconnectionRequested.emit(false)

        requestAction(MainActivity.MainActivityScreenAction.SYNC_TIME)
      } catch (e: Exception) {
        Log.e("Firmware update", "Failed to update firmware", e)
        // TODO show prompt which would recommend to restart the watch before next attempt
      } finally {
        _windowFlagRemoved.emit(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }
  }

  private suspend fun unzipFirmware(firmwareUri: Uri): File {
    return withContext(Dispatchers.IO) {
      val tmpDir = File(applicationContext.cacheDir, "tmp_firmware")
        .also(File::deleteRecursively) // To make sure we don't have leftovers

      if (!tmpDir.mkdir()) {
        throw IllegalStateException("Can't create tmp folder for firmware")
      }
      applicationContext.contentResolver.openInputStream(firmwareUri)?.use { inputStream ->
        ZipInputStream(inputStream).use { zipStream ->
          zipStream.unzipAll(tmpDir)
        }
      }

      tmpDir
    }
  }

  private suspend fun uploadFirmware(
    connection: BluetoothConnection,
    firmwareFolder: File,
  ) {
    require(firmwareFolder.isDirectory)

    withContext(Dispatchers.Default) {
      connection.perform {
        val firmwareFiles = firmwareFolder.listFiles()!!

        val fileDat = firmwareFiles.first { ".dat" in it.name }
        val fileBin = firmwareFiles.first { ".bin" in it.name }
        val fileBinSize = fileBin.length()

        val controlPointCharacteristic =
          findCharacteristic(UUID.fromString("00001531-1212-efde-1523-785feabcd123"))
        val packetCharacteristic =
          findCharacteristic(UUID.fromString("00001532-1212-efde-1523-785feabcd123"))

        checkNotNull(packetCharacteristic)
        checkNotNull(controlPointCharacteristic)

        controlPointCharacteristic.enableNotifications()

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
      }
    }
  }

  private fun syncTime(connection: BluetoothConnection) {
    viewModelScope.launch(Dispatchers.Default) {
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

  private fun observeStateChanges() {
    val requiredPermissionsAndServices = combine(
      locationEnabled,
      bluetoothEnabled,
      permissionsGranted,
    ) { locationEnabled, bluetoothEnabled, permissionsGranted ->
      when {
        !permissionsGranted -> MainActivity.ViewState.PermissionsRequired
        !locationEnabled -> MainActivity.ViewState.ServicesRequired.Location
        !bluetoothEnabled -> MainActivity.ViewState.ServicesRequired.Bluetooth
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
        MainActivity.ViewState.DevicesDiscovery(
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
          MainActivity.ViewState.ConnectedDevice(
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
      .launchIn(viewModelScope)

    combine(
      discovery,
      connectedDeviceInfo,
      requiredPermissionsAndServices,
    ) { discovery, connectedDeviceInfo, permissionsAndServices ->
      connectedDeviceInfo
        ?: discovery
        ?: permissionsAndServices
        ?: MainActivity.ViewState.Loading
    }
      .sample(300L)
      .flowOn(Dispatchers.Default)
      .onEach(_state::emit)
      .launchIn(viewModelScope)
  }

  private fun fetchFirmwareVersion(connection: BluetoothConnection) {
    viewModelScope.launch(Dispatchers.Default) {
      val firmwareVersionString = connection.performForResult {
        val firmwareVersionChar = findCharacteristic(
          UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        )

        firmwareVersionChar?.read()?.decodeToString()
      }

      firmwareVersion.emit(firmwareVersionString)
    }
  }

  private fun checkRequiredPermissionsAndServices() {
    locationEnabled.tryEmit(
      LocationManagerCompat.isLocationEnabled(applicationContext.getSystemService(LocationManager::class.java))
    )
    bluetoothEnabled.tryEmit(
      applicationContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    )
    permissionsGranted.tryEmit(
      applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    )
    notificationsAccessGranted.tryEmit(
      BuildConfig.APPLICATION_ID in applicationContext.notificationsListeners
    )
  }

  private val Context.notificationsListeners: Set<String>
    get() = NotificationManagerCompat.getEnabledListenerPackages(this.applicationContext)

  companion object {
    private const val KEY_SHARED_PREF = "pine_pal_shared_prefs"
    private const val KEY_CONNECTED_DEVICE_MAC = "connected_device_mac"

    private const val DFU_SEGMENT_SIZE = 20
  }
}
