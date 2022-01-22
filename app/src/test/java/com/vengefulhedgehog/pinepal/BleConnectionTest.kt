package com.vengefulhedgehog.pinepal

import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

class WatchActionsTest : FunSpec({

  lateinit var testDispatcher: TestCoroutineDispatcher
  lateinit var testCoroutineScope: TestCoroutineScope

  beforeEach {
    testDispatcher = TestCoroutineDispatcher()
    testCoroutineScope = TestCoroutineScope(testDispatcher)

    Dispatchers.setMain(testDispatcher)
  }

  afterEach {
    Dispatchers.resetMain()
    testCoroutineScope.cleanupTestCoroutines()
    testDispatcher.cleanupTestCoroutines()
  }

  test("DFU: Success") {

  }
})
