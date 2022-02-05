package com.vengefulhedgehog.pinepal

import androidx.lifecycle.ViewModel
import com.vengefulhedgehog.pinepal.domain.handler.SystemEvent
import com.vengefulhedgehog.pinepal.domain.handler.SystemEventsHandler
import com.vengefulhedgehog.pinepal.domain.usecases.BluetoothUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.LocationUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.NotificationsUseCase
import com.vengefulhedgehog.pinepal.domain.usecases.SystemEventsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
  private val locationUseCase: LocationUseCase,
  private val bluetoothUseCase: BluetoothUseCase,
  private val systemEventsUseCase: SystemEventsUseCase,
  private val notificationsUseCase: NotificationsUseCase,
) : ViewModel() {

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

  private val _navigationEvent = MutableSharedFlow<SystemEvent.Navigation>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val navigationEvent = _navigationEvent.asSharedFlow()

  private val eventsHandler = object : SystemEventsHandler {
    override fun onEvent(event: SystemEvent): Boolean {
      when (event) {
        is SystemEvent.WindowFlagAdd -> _windowFlagAdded.tryEmit(event.flag)
        is SystemEvent.WindowFlagRemove -> _windowFlagRemoved.tryEmit(event.flag)
        is SystemEvent.Navigation -> _navigationEvent.tryEmit(event)
      }

      return true
    }
  }

  init {
    systemEventsUseCase.registerHandler(eventsHandler)
  }

  override fun onCleared() {
    systemEventsUseCase.unregisterHandler(eventsHandler)

    super.onCleared()
  }

  fun onPermissionCheckRequested() {
    locationUseCase.checkAvailability()
    bluetoothUseCase.checkAvailability()
    notificationsUseCase.checkNotificationsAccess()
  }
}
