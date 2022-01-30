package com.vengefulhedgehog.pinepal.domain.usecases.deviceactions

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.di.annotations.ApplicationScope
import com.vengefulhedgehog.pinepal.domain.model.notification.PineTimeNotification
import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertNotificationServiceUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  @ApplicationScope private val appScope: CoroutineScope,
) {

  private val pendingNotification = MutableSharedFlow<PineTimeNotification>(
    extraBufferCapacity = 128,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  private var notificationJob: Job? = null
  private var notificationCharacteristic: BluetoothGattCharacteristic? = null

  init {
    activeConnectionUseCase.connectedDevice
      .onEach { connection ->
        if (connection != null) {
          startSendingToDevice()
        } else {
          notificationJob?.cancel()
          notificationCharacteristic = null
        }
      }
      .launchIn(appScope)
  }

  fun notify(notification: PineTimeNotification) {
    pendingNotification.tryEmit(notification)
  }

  private fun startSendingToDevice() {
    notificationJob = activeConnectionUseCase.connectedDevice
      .filterNotNull()
      .combine(pendingNotification) { connection, notification ->
        connection.perform {
          val characteristic = notificationCharacteristic
            ?: findCharacteristic(UUID_NOTIFICATION)?.also { characteristic ->
              notificationCharacteristic = characteristic
            }

          val notificationBytes = byteArrayOf(
            notification.category.code,
            0x01.toByte(), // amount of notifications
            CONTENT_SEPARATOR
          ) +
              notification.title.encodeToByteArray() +
              CONTENT_SEPARATOR +
              notification.body.encodeToByteArray()

          try {
            characteristic!!.write(notificationBytes)
          } catch (e: Exception) {
            Log.e("ConnectionService", "Couldn't send notification", e)
          }
        }
      }
      .flowOn(dispatchers.default)
      .launchIn(appScope)
  }

  companion object {
    private val UUID_NOTIFICATION = UUID.fromString("00002a46-0000-1000-8000-00805f9b34fb")

    private const val CONTENT_SEPARATOR = 0x00.toByte()
  }
}
