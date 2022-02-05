package com.vengefulhedgehog.pinepal.domain.handler

sealed interface SystemEvent {

  data class WindowFlagAdd(val flag: Int) : SystemEvent

  data class WindowFlagRemove(val flag: Int) : SystemEvent

  sealed interface Navigation : SystemEvent {
    data class To(val direction: String) : Navigation

    data class Back(val direction: String? = null) : Navigation
  }
}
