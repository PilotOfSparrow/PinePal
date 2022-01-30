package com.vengefulhedgehog.pinepal.services

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.view.KeyEvent
import com.vengefulhedgehog.pinepal.App
import com.vengefulhedgehog.pinepal.domain.model.media.ActiveMediaInfo
import com.vengefulhedgehog.pinepal.domain.usecases.NotificationsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class PineTimeConnectionService : Service() {

  @Inject
  lateinit var notificationsUseCase: NotificationsUseCase

  private val connectionScope = CoroutineScope(Dispatchers.Default + Job())

  private val activeMediaInfo: StateFlow<ActiveMediaInfo?> by lazy {
    (application as App).activeMediaInfo
  }

  private val audioManager by lazy { getSystemService(AudioManager::class.java) }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(
      notificationsUseCase.getForegroundNotificationId(),
      notificationsUseCase.getForegroundNotification()
    )

//    activeMediaInfo
//      .onEach(this::sendMediaInfo)
//      .launchIn(connectionScope)

    return super.onStartCommand(intent, flags, startId)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    connectionScope.cancel()

    super.onDestroy()
  }

//  private fun sendMediaInfo(activeMediaInfo: ActiveMediaInfo?) {
//    val connection = activeConnectionUseCase.getConnectedDevice() ?: return
//
//    connection.performInScope {
//      val statusChar = findCharacteristic(UUID_MEDIA_STATUS)
//      val artistChar = findCharacteristic(UUID_MEDIA_ARTIST)
//      val trackChar = findCharacteristic(UUID_MEDIA_TRACK)
//      val albumChar = findCharacteristic(UUID_MEDIA_ALBUM)
//
//      statusChar?.write(activeMediaInfo?.encodedStatus ?: ByteArray(1))
//      artistChar?.write(activeMediaInfo?.encodeArtist ?: ByteArray(1))
//      trackChar?.write(activeMediaInfo?.encodedTitle ?: ByteArray(1))
//      albumChar?.write(activeMediaInfo?.encodedAlbum ?: ByteArray(1))
//    }
//  }
//
//  private fun subscribeToMediaEvents(connection: BluetoothConnection) {
//    connection.performInScope {
//      findCharacteristic(UUID_MEDIA_EVENTS)?.let { mediaChar ->
//        mediaChar.enableNotifications()
//        mediaChar
//          .observeNotifications()
//          .map { eventData -> eventData.firstOrNull()?.toMediaEvent() }
//          .filterNotNull()
//          .onEach { mediaEvent ->
//            when (mediaEvent) {
//              MediaEvent.APP_OPEN -> {
//                activeMediaInfo.value?.let { activeMediaInfo ->
//                  sendMediaInfo(activeMediaInfo)
//                }
//              }
//              MediaEvent.PLAY -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
//              MediaEvent.PAUSE -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
//              MediaEvent.NEXT -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
//              MediaEvent.PREVIOUS -> audioManager.sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
//              MediaEvent.VOLUME_UP -> audioManager.volumeUp()
//              MediaEvent.VOLUME_DOWN -> audioManager.volumeDown()
//            }
//          }
//          .launchIn(connectionScope)
//      }
//    }
//  }

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
    private val UUID_MEDIA_EVENTS = UUID.fromString("00000001-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_STATUS = UUID.fromString("00000002-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_ARTIST = UUID.fromString("00000003-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_TRACK = UUID.fromString("00000004-78fc-48fe-8e23-433b3a1942d0")
    private val UUID_MEDIA_ALBUM = UUID.fromString("00000005-78fc-48fe-8e23-433b3a1942d0")
  }
}
