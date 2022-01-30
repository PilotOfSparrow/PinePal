package com.vengefulhedgehog.pinepal.domain.model.notification

enum class PineTimeNotificationCategory(val code: Byte) {
  SIMPLE(0x00),
  EMAIL(0x01),
  NEWS(0x02),
  CALL(0x03),
  MISSED_CALL(0x04),
  SMS(0x05),
  VOICEMAIL(0x06),
  SCHEDULE(0x07),
  HIGH_PRIORITY(0x08),
  INSTANT_MESSAGE(0x09),
  ALL(0xFF.toByte())
}
