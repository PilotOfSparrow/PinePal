package com.vengefulhedgehog.pinepal.domain.usecases.deviceactions

import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import javax.inject.Inject

class TimeSyncUseCase @Inject constructor(
  private val activeConnectionUseCase: ActiveConnectionUseCase,
) {
  suspend fun sync() {
    withContext(Dispatchers.Default) {
      val connection = activeConnectionUseCase.getConnectedDevice()
        ?: throw IllegalStateException("Can't sync time without active connection")

      connection.perform {
        val timeChar = findCharacteristic(UUID_TIME)

        val time = LocalDateTime.now()
        val microseconds = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now()) / 1e6 * 256
        val timeArray = ByteBuffer
          .allocate(10)
          .order(ByteOrder.LITTLE_ENDIAN)
          .put((time.year and 0xFF).toByte())
          .put((time.year.shr(8) and 0xFF).toByte())
          .put(time.month.value.toByte())
          .put(time.dayOfMonth.toByte())
          .put(time.hour.toByte())
          .put(time.minute.toByte())
          .put(time.second.toByte())
          .put(time.dayOfWeek.value.toByte())
          .put(microseconds.toInt().toByte())
          .put(0x0001)
          .array()

        timeChar?.write(timeArray)
      }
    }
  }

  companion object {
    private val UUID_TIME = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
  }
}
