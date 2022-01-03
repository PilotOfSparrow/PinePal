package com.vengefulhedgehog.pinepal.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vengefulhedgehog.pinepal.App

class PinePalNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    println(activeNotifications)
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    println("NOTIFICATION POSTED BEATCH $sbn")
    val title = sbn.notification.extras.getString("android.title")
    val body = sbn.notification.extras.getString("android.text")
    if (title != null && body != null) {
      (application as App).notification.tryEmit(
        title to body
      )
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    println("NOTIFICATION REMOVED BEATCH $sbn")
  }
}
