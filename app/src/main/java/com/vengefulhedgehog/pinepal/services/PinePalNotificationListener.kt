package com.vengefulhedgehog.pinepal.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vengefulhedgehog.pinepal.App
import com.vengefulhedgehog.pinepal.domain.notification.PineTimeNotification

class PinePalNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() = Unit

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.id == 37) return

    val title = sbn.notification.extras.getString("android.title")
    val body = sbn.notification.extras.getString("android.text")

    if (title != null && body != null) {
      (application as App).notification.tryEmit(
        PineTimeNotification(
          title = title,
          body = body,
        )
      )
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
  }
}
