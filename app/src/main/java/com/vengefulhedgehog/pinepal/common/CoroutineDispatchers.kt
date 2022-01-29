package com.vengefulhedgehog.pinepal.common

import kotlinx.coroutines.CoroutineDispatcher

interface CoroutineDispatchers {
  val io: CoroutineDispatcher
  val main: CoroutineDispatcher
  val default: CoroutineDispatcher
  val unconfined: CoroutineDispatcher
}
