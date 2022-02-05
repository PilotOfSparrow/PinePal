package com.vengefulhedgehog.pinepal.domain.usecases

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.vengefulhedgehog.pinepal.BuildConfig
import com.vengefulhedgehog.pinepal.bluetooth.BleConnectionState
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.di.annotations.ApplicationScope
import com.vengefulhedgehog.pinepal.domain.model.notification.PineTimeNotification
import com.vengefulhedgehog.pinepal.domain.model.notification.PineTimeNotificationCategory
import com.vengefulhedgehog.pinepal.domain.usecases.deviceactions.AlertNotificationServiceUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationsUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  private val deviceStatisticsUseCase: DeviceStatisticsUseCase,
  private val alertNotificationServiceUseCase: AlertNotificationServiceUseCase,
  @ApplicationScope private val appScope: CoroutineScope,
  @ApplicationContext private val context: Context,
) {
  private val _hasNotificationsAccess = MutableStateFlow(false)
  val hasNotificationsAccess = _hasNotificationsAccess.asStateFlow()

  private val pendingNotifications = MutableSharedFlow<StatusBarNotification>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  private val notificationsListeners: Set<String>
    get() = NotificationManagerCompat.getEnabledListenerPackages(context)

  private val notificationManager by lazy { NotificationManagerCompat.from(context) }
  private val notificationBuilder by lazy {
    Notification.Builder(context, ID_CONNECTION_CHANNEL)
      .setContentTitle("PineTime")
      .setContentText("Awaiting watch info (HR, steps)")
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
  }

  init {
    ensureNotificationChannelExists()

    observePendingNotifications()

    upkeepAppNotificationContent()
  }

  fun checkNotificationsAccess() {
    _hasNotificationsAccess.tryEmit(
      BuildConfig.APPLICATION_ID in notificationsListeners
    )
  }

  fun getForegroundNotification() = notificationBuilder.build()

  fun getForegroundNotificationId(): Int = ID_NOTIFICATION

  fun registerNotification(systemNotification: StatusBarNotification) {
    pendingNotifications.tryEmit(systemNotification)
  }

  private fun upkeepAppNotificationContent() {
    val connectionState = activeConnectionUseCase.connectedDevice.flatMapLatest { connection ->
      connection?.state ?: flowOf(BleConnectionState.DISCONNECTED)
    }

    combine(
      deviceStatisticsUseCase.currentStatistics,
      connectionState,
    ) { statistics, connectionState ->
      val title = connectionState.name.lowercase().replaceFirstChar(Char::uppercase)
      val text = "HR: ${statistics.heartRate} " +
          "Steps: ${statistics.steps} " +
          "Battery: ${statistics.batteryLevel}%"

      notificationBuilder
        .setContentTitle(title)
        .setContentText(text)
        .build()
    }
      .sample(2_000L)
      .onEach { notification ->
        notificationManager.notify(
          ID_NOTIFICATION,
          notification
        )
      }
      .launchIn(appScope)
  }

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

  private fun observePendingNotifications() {
    pendingNotifications
      .filterNot { sbn ->
        sbn.isOngoing ||
            sbn.id == ID_NOTIFICATION ||
            sbn.notification.extras.get("android.mediaSession") != null
      }
      .onEach { notification ->
        val pineNotification = PineTimeNotification(
          title = notification.notification.extras.getString("android.title").orEmpty(),
          body = notification.notification.extras.getString("android.text").orEmpty(),
          category = PineTimeNotificationCategory.SIMPLE,
        )

        alertNotificationServiceUseCase.notify(pineNotification)
      }
      .flowOn(dispatchers.default)
      .launchIn(appScope)
  }

  companion object {
    private const val ID_NOTIFICATION = 37
    private const val ID_CONNECTION_CHANNEL = "connection_channel"
  }
}
