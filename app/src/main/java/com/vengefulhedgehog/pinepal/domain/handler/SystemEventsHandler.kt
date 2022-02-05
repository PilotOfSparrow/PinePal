package com.vengefulhedgehog.pinepal.domain.handler

interface SystemEventsHandler {

  fun onEvent(event: SystemEvent): Boolean

}
