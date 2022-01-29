package com.vengefulhedgehog.pinepal.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class AppCoroutineDispatchers : CoroutineDispatchers {
  override val io: CoroutineDispatcher = Dispatchers.IO
  override val main: CoroutineDispatcher = Dispatchers.Main
  override val default: CoroutineDispatcher = Dispatchers.Default
  override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
