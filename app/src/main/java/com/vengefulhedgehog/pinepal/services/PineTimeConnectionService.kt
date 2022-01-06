package com.vengefulhedgehog.pinepal.services

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.vengefulhedgehog.pinepal.App
import com.vengefulhedgehog.pinepal.bluetooth.BleConnectionState
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.domain.notification.PineTimeNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.*

class PineTimeConnectionService : Service() {

  private val connectionScope = CoroutineScope(Dispatchers.Default + Job())

  private val stepsFlow = MutableStateFlow(0)
  private val heartRateFlow = MutableStateFlow(0)
  private val connectionStateFlow = MutableStateFlow(BleConnectionState.DISCONNECTED)

  private val notificationManager by lazy { NotificationManagerCompat.from(applicationContext) }
  private val notificationBuilder by lazy {
    Notification.Builder(applicationContext, ID_CONNECTION_CHANNEL)
      .setContentTitle("PineTime")
      .setContentText("Awaiting watch info (HR, steps)")
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
  }

  private var notificationCharacteristic: BluetoothGattCharacteristic? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureChannelExists()

    startForeground(ID_NOTIFICATION, notificationBuilder.build())

    observeConnectedDevice()

    upkeepPhoneNotificationContent()

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
      connectionStateFlow,
    ) { steps, hr, connectionState ->
      val title = if (connectionState == BleConnectionState.CONNECTED) {
        "Connected"
      } else {
        "Disconnected"
      }

      title to "HR: $hr Steps: $steps"
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
    (application as App).connectedDevice
      .filterNotNull()
      .onEach { connection ->
        subscribeToSteps(connection)
        subscribeToHeartRate(connection)
        subscribeToConnectionState(connection)
      }
      .combine(observeNotifications()) { connection, notification ->
        sendNotification(connection, notification)
      }
      .launchIn(connectionScope)
  }

  private fun sendNotification(
    connection: BluetoothConnection,
    notification: PineTimeNotification
  ) {
    connectionScope.launch {
      val characteristic = notificationCharacteristic
        ?: connection.findCharacteristic(UUID_NOTIFICATION)?.also { characteristic ->
          notificationCharacteristic = characteristic
        }

      characteristic?.let {
        connection.apply {
          val notificationBytes = byteArrayOf(
            0x00.toByte(), // category
            0x01.toByte(), // amount of notifications
            0x00.toByte()  // content separator
          ) +
              notification.title.encodeToByteArray() +
              0x00.toByte() +
              notification.body.encodeToByteArray()

          characteristic.write(notificationBytes)
        }
      }
    }
  }

  private fun observeNotifications() =
    (application as App).notification
      .debounce(2_000L)

  private fun ensureChannelExists() {
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
    connection.applyInScope {
      findCharacteristic(UUID_HEART_RATE)
        ?.let { char ->
          enableNotificationsFor(char, UUID_DESCRIPTOR_NOTIFY)

          char.observeNotifications()
        }
        ?.map { ByteBuffer.wrap(it).short.toInt() }
        ?.onEach(heartRateFlow::emit)
        ?.launchIn(connectionScope)
    }
  }

  private fun subscribeToSteps(connection: BluetoothConnection) {
    connection.applyInScope {
      findCharacteristic(UUID_MOTION)
        ?.let { motionChar ->
          enableNotificationsFor(motionChar, UUID_DESCRIPTOR_NOTIFY)

          motionChar.observeNotifications()
        }
        ?.map { it.firstOrNull()?.toInt() ?: 0 }
        ?.onEach(stepsFlow::emit)
        ?.launchIn(connectionScope)
    }
  }

  private fun subscribeToConnectionState(connection: BluetoothConnection) {
    connection.state
      .onEach(connectionStateFlow::emit)
      .launchIn(connectionScope)
  }

  private fun BluetoothConnection.applyInScope(
    scope: CoroutineScope = connectionScope,
    block: suspend BluetoothConnection.() -> Unit,
  ): BluetoothConnection {
    scope.launch { block() }

    return this
  }

  companion object {
    private const val ID_NOTIFICATION = 37
    private const val ID_CONNECTION_CHANNEL = "connection_channel"

    private val UUID_HEART_RATE = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    private val UUID_MOTION = UUID.fromString("00030001-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_NOTIFICATION = UUID.fromString("00002a46-0000-1000-8000-00805f9b34fb")

    private val UUID_DESCRIPTOR_NOTIFY = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }
}
