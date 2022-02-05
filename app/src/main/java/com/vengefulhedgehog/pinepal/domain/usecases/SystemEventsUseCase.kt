package com.vengefulhedgehog.pinepal.domain.usecases

import com.vengefulhedgehog.pinepal.domain.handler.SystemEvent
import com.vengefulhedgehog.pinepal.domain.handler.SystemEventsHandler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemEventsUseCase @Inject constructor() {

  private val eventHandlers = CopyOnWriteArrayList<SystemEventsHandler>()

  private val _windowFlagAdded = MutableSharedFlow<Int>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  private val _windowFlagRemoved = MutableSharedFlow<Int>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  fun registerHandler(handler: SystemEventsHandler) {
//    CopyOnWriteArraySet doesn't guaranties order and iteration is slower
    unregisterHandler(handler) // To make sure there is no copies

    eventHandlers += handler
  }

  fun unregisterHandler(handler: SystemEventsHandler) {
    eventHandlers -= handler
  }

  fun send(event: SystemEvent) {
    if (eventHandlers.lastOrNull()?.onEvent(event) == true) return

    for (i in eventHandlers.lastIndex downTo 0) {
      if (eventHandlers.getOrNull(i)?.onEvent(event) == true) return
    }
  }
}
