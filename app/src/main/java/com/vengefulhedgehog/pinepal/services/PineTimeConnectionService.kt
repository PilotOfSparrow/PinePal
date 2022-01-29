package com.vengefulhedgehog.pinepal.services

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.vengefulhedgehog.pinepal.App
import com.vengefulhedgehog.pinepal.bluetooth.BleConnectionState
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.domain.model.media.ActiveMediaInfo
import com.vengefulhedgehog.pinepal.domain.model.notification.PineTimeNotification
import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.time.Instant
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class PineTimeConnectionService : Service() {

  @Inject
  lateinit var activeConnectionUseCase: ActiveConnectionUseCase

  private val connectionScope = CoroutineScope(Dispatchers.Default + Job())

  private val stepsFlow = MutableStateFlow(0)
  private val heartRateFlow = MutableStateFlow(0)
  private val batteryLevelFlow = MutableStateFlow(-1)
  private val connectionStateFlow = MutableStateFlow(BleConnectionState.DISCONNECTED)

  private val activeMediaInfo: StateFlow<ActiveMediaInfo?> by lazy {
    (application as App).activeMediaInfo
  }

  private val notificationManager by lazy { NotificationManagerCompat.from(applicationContext) }
  private val notificationBuilder by lazy {
    Notification.Builder(applicationContext, ID_CONNECTION_CHANNEL)
      .setContentTitle("PineTime")
      .setContentText("Awaiting watch info (HR, steps)")
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
  }

  private val audioManager by lazy { getSystemService(AudioManager::class.java) }

  private var notificationCharacteristic: BluetoothGattCharacteristic? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureNotificationChannelExists()

    startForeground(ID_NOTIFICATION, notificationBuilder.build())

    observeConnectedDevice()

    upkeepPhoneNotificationContent()

    activeMediaInfo
      .onEach(this::sendMediaInfo)
      .launchIn(connectionScope)

    return super.onStartCommand(intent, flags, startId)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    connectionScope.cancel()

    super.onDestroy()
  }

  private fun upkeepPhoneNotificationContent() {
    combine(
      stepsFlow,
      heartRateFlow,
      batteryLevelFlow,
      connectionStateFlow,
    ) { steps, hr, batteryLevel, connectionState ->
      val title = if (connectionState == BleConnectionState.CONNECTED) {
        "Connected"
      } else {
        "Disconnected"
      }

      title to "HR: $hr Steps: $steps Battery: ${batteryLevel}%"
    }
      .sample(2_000L)
      .onEach { (title, text) ->
        notificationManager.notify(
          ID_NOTIFICATION,
          notificationBuilder
            .setContentTitle(title)
            .setContentText(text).build()
        )
      }
      .launchIn(connectionScope)
  }

  private fun observeConnectedDevice() {
    activeConnectionUseCase.connectedDevice
      .filterNotNull()
      .onEach { connection ->
        subscribeToSteps(connection)
        subscribeToHeartRate(connection)
        subscribeToMediaEvents(connection)
        subscribeToBatteryLevel(connection)
        subscribeToConnectionState(connection)
      }
      .combine(observeNotifications()) { connection, notification ->
        notify(connection, notification)
      }
      .launchIn(connectionScope)
  }

  private fun sendMediaInfo(activeMediaInfo: ActiveMediaInfo?) {
    val connection = activeConnectionUseCase.connectedDevice.value ?: return

    connection.performInScope {
      val statusChar = findCharacteristic(UUID_MEDIA_STATUS)
      val artistChar = findCharacteristic(UUID_MEDIA_ARTIST)
      val trackChar = findCharacteristic(UUID_MEDIA_TRACK)
      val albumChar = findCharacteristic(UUID_MEDIA_ALBUM)

      statusChar?.write(activeMediaInfo?.encodedStatus ?: ByteArray(1))
      artistChar?.write(activeMediaInfo?.encodeArtist ?: ByteArray(1))
      trackChar?.write(activeMediaInfo?.encodedTitle ?: ByteArray(1))
      albumChar?.write(activeMediaInfo?.encodedAlbum ?: ByteArray(1))
    }
  }

  private fun notify(
    connection: BluetoothConnection,
    notification: PineTimeNotification
  ) {
    connectionScope.launch {
      connection.perform {
        val characteristic = notificationCharacteristic
          ?: findCharacteristic(UUID_NOTIFICATION)?.also { characteristic ->
            notificationCharacteristic = characteristic
          }

        characteristic?.let {
          val notificationBytes = byteArrayOf(
            0x00.toByte(), // category
            0x01.toByte(), // amount of notifications
            0x00.toByte()  // content separator
          ) +
              notification.title.encodeToByteArray() +
              0x00.toByte() +
              notification.body.encodeToByteArray()

          try {
            characteristic.write(notificationBytes)
          } catch (e: Exception) {
            Log.e("ConnectionService", "Couldn't send notification", e)
          }
        }
      }
    }
  }

  private fun observeNotifications() =
    (application as App).notification
      .debounce(2_000L)

  private fun ensureNotificationChannelExists() {
    notificationManager.getNotificationChannel(ID_CONNECTION_CHANNEL) ?: let {
      notificationManager.createNotificationChannel(
        NotificationChannelCompat
          .Builder(ID_CONNECTION_CHANNEL, 0)
          .setName("Connection")
          .build()
      )
    }
  }

  private fun subscribeToHeartRate(connection: BluetoothConnection) {
    logToFileHeartRate(connection.device.address)

    connection.performInScope {
      findCharacteristic(UUID_HEART_RATE)
        ?.let { hrChar ->
          hrChar.enableNotifications()

          merge(
            flowOf(hrChar.read()),
            hrChar.observeNotifications(),
          )
        }
        ?.map { hrByteArray ->
          if (hrByteArray == null || hrByteArray.isEmpty()) {
            0
          } else {
            ByteBuffer.wrap(hrByteArray).short.toInt()
          }
        }
        ?.onEach(heartRateFlow::emit)
        ?.launchIn(connectionScope)
    }
  }

  private fun logToFileHeartRate(deviceMac: String) {
    connectionScope.launch(Dispatchers.IO) {
      val hrOutput = applicationContext.openFileOutput("HR:$deviceMac", Context.MODE_APPEND)

      heartRateFlow
        .filter { it > 0 }
        .conflate()
        .onEach { heartRate ->
          hrOutput.write("${Instant.now()};$heartRate\n".toByteArray())
        }
        .onCompletion { hrOutput.close() }
        .catch { hrOutput.close() }
        .launchIn(connectionScope)
    }
  }

  private fun subscribeToSteps(connection: BluetoothConnection) {
    logToFileSteps(connection.device.address)

    connection.performInScope {
      findCharacteristic(UUID_MOTION)
        ?.let { motionChar ->
          motionChar.enableNotifications()

          merge(
            flowOf(motionChar.read()),
            motionChar.observeNotifications(),
          )
        }
        ?.map { motionData ->
          val steps = motionData?.firstOrNull()?.toInt() ?: 0

          steps
        }
        ?.onEach(stepsFlow::emit)
        ?.launchIn(connectionScope)
    }
  }

  private fun logToFileSteps(deviceMac: String) {
    connectionScope.launch(Dispatchers.IO) {
      val hrOutput = applicationContext.openFileOutput("Steps:$deviceMac", Context.MODE_APPEND)

      stepsFlow
        .filter { it > 0 }
        .conflate()
        .onEach { steps ->
          hrOutput.write("${Instant.now()};$steps\n".toByteArray())
        }
        .onCompletion { hrOutput.close() }
        .catch { hrOutput.close() }
        .launchIn(connectionScope)
    }
  }

  private fun subscribeToMediaEvents(connection: BluetoothConnection) {
    connection.performInScope {
      findCharacteristic(UUID_MEDIA_EVENTS)?.let { mediaChar ->
        mediaChar.enableNotifications()
        mediaChar
          .observeNotifications()
          .map { eventData -> eventData.firstOrNull()?.toMediaEvent() }
          .filterNotNull()
          .onEach { mediaEvent ->
            when (mediaEvent) {
              MediaEvent.APP_OPEN -> {
                activeMediaInfo.value?.let { activeMediaInfo ->
                  sendMediaInfo(activeMediaInfo)
                }
              }
              MediaEvent.PLAY -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
              MediaEvent.PAUSE -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
              MediaEvent.NEXT -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
              MediaEvent.PREVIOUS -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
              MediaEvent.VOLUME_UP -> audioManager.volumeUp()
              MediaEvent.VOLUME_DOWN -> audioManager.volumeDown()
            }
          }
          .launchIn(connectionScope)
      }
    }
  }

  private fun subscribeToBatteryLevel(connection: BluetoothConnection) {
    connection.performInScope {
      findCharacteristic(UUID_BATTERY_LEVEL)
        ?.let { batteryLevelChar ->
          batteryLevelChar.enableNotifications()

          merge(
            flowOf(batteryLevelChar.read()),
            batteryLevelChar.observeNotifications(),
          )
        }
        ?.map { batteryLevel ->
          batteryLevel
            ?.takeIf(ByteArray::isNotEmpty)
            ?.firstOrNull()
            ?.toInt()
            ?: 0
        }
        ?.onEach(batteryLevelFlow::emit)
        ?.launchIn(connectionScope)
    }
  }

  private fun subscribeToConnectionState(connection: BluetoothConnection) {
    connection.state
      .onEach(connectionStateFlow::emit)
      .launchIn(connectionScope)
  }

  private fun AudioManager.volumeUp() {
    adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
  }

  private fun AudioManager.volumeDown() {
    adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
  }

  private fun AudioManager.sendKeyEvent(event: Int) {
    dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, event))
    dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, event))
  }

  private fun BluetoothConnection.performInScope(
    scope: CoroutineScope = connectionScope,
    block: suspend BluetoothConnection.BleActions.() -> Unit,
  ): BluetoothConnection {
    scope.launch { perform(block) }

    return this
  }

  private fun Byte.toMediaEvent(): MediaEvent? = when (this) {
    0xe0.toByte() -> MediaEvent.APP_OPEN
    0x00.toByte() -> MediaEvent.PLAY
    0x01.toByte() -> MediaEvent.PAUSE
    0x03.toByte() -> MediaEvent.NEXT
    0x04.toByte() -> MediaEvent.PREVIOUS
    0x05.toByte() -> MediaEvent.VOLUME_UP
    0x06.toByte() -> MediaEvent.VOLUME_DOWN
    else -> null
  }

  private enum class MediaEvent {
    APP_OPEN,

    PLAY,
    PAUSE,

    NEXT,
    PREVIOUS,

    VOLUME_UP,
    VOLUME_DOWN,
  }

  companion object {
    private const val ID_NOTIFICATION = 37
    private const val ID_CONNECTION_CHANNEL = "connection_channel"

    private val UUID_MOTION = UUID.fromString("00030001-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_HEART_RATE = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val UUID_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val UUID_MEDIA_EVENTS = UUID.fromString("00000001-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_STATUS = UUID.fromString("00000002-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_ARTIST = UUID.fromString("00000003-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_TRACK = UUID.fromString("00000004-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_ALBUM = UUID.fromString("00000005-78fc-48fe-8e23-433b3a1942d0")

    private val UUID_NOTIFICATION = UUID.fromString("00002a46-0000-1000-8000-00805f9b34fb")
  }
}
