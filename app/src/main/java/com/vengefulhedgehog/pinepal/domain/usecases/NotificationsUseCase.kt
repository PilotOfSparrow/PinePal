package com.vengefulhedgehog.pinepal.domain.usecases

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.vengefulhedgehog.pinepal.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationsUseCase @Inject constructor(
  @ApplicationContext private val context: Context,
){
  private val _hasNotificationsAccess = MutableStateFlow(false)
  val hasNotificationsAccess = _hasNotificationsAccess.asStateFlow()

  private val notificationsListeners: Set<String>
    get() = NotificationManagerCompat.getEnabledListenerPackages(context)

  fun checkNotificationsAccess() {
    _hasNotificationsAccess.tryEmit(
      BuildConfig.APPLICATION_ID in notificationsListeners
    )
  }
}
