package com.vengefulhedgehog.pinepal.services

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vengefulhedgehog.pinepal.App
import com.vengefulhedgehog.pinepal.domain.media.ActiveMediaInfo
import com.vengefulhedgehog.pinepal.domain.notification.PineTimeNotification

class PinePalNotificationListener : NotificationListenerService() {

  private val mediaSessionManager: MediaSessionManager
    get() = applicationContext.getSystemService(MediaSessionManager::class.java)

  private var activeMediaController: MediaController? = null

  private val mediaSessionListener =
    MediaSessionManager.OnActiveSessionsChangedListener { mediaSessions ->
      onActiveMediaSessionsChanged(mediaSessions.orEmpty())
    }

  override fun onListenerConnected() {
    val component = ComponentName(applicationContext, PinePalNotificationListener::class.java)

    onActiveMediaSessionsChanged(mediaSessionManager.getActiveSessions(component))

    mediaSessionManager.addOnActiveSessionsChangedListener(mediaSessionListener, component)
  }

  override fun onDestroy() {
    mediaSessionManager.removeOnActiveSessionsChangedListener(mediaSessionListener)

    super.onDestroy()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (!sbn.isOngoing) return
    if (sbn.notification.extras.get("android.mediaSession") != null) return

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

  override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

  private fun onActiveMediaSessionsChanged(activeSessions: List<MediaController>) {
    activeMediaController = activeSessions.firstOrNull()

    activeSessions.forEach { mediaController ->
      if (mediaController.playbackState?.state == PlaybackState.STATE_PLAYING) {
        activeMediaController = mediaController
      }
      mediaController.registerCallback(object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
          if (state?.state == PlaybackState.STATE_PLAYING) {
            activeMediaController = mediaController
          }

          onActiveMediaControllerChanged()
        }
      })
    }

    onActiveMediaControllerChanged()
  }

  private fun onActiveMediaControllerChanged() {
    activeMediaController
      ?.let { controller ->
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val album = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val currentPosition = controller.playbackState?.position ?: 0L

        ActiveMediaInfo(
          title = title,
          album = album,
          artist = artist,
          isPlaying = playing,
          totalDuration = duration,
          currentPlayPosition = currentPosition,
        )
      }
      .let((application as App).activeMediaInfo::tryEmit)
  }
}
