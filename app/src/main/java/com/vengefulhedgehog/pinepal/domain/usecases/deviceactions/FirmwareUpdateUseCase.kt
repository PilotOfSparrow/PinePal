package com.vengefulhedgehog.pinepal.domain.usecases.deviceactions

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.domain.model.bluetooth.DfuProgress
import com.vengefulhedgehog.pinepal.domain.usecases.ActiveConnectionUseCase
import com.vengefulhedgehog.pinepal.extensions.unzipAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.zip.ZipInputStream
import javax.inject.Inject

class FirmwareUpdateUseCase @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val activeConnectionUseCase: ActiveConnectionUseCase,
  @ApplicationContext private val context: Context,
) {

  private val _dfuProgress = MutableStateFlow<DfuProgress?>(null)
  val dfuProgress = _dfuProgress.asStateFlow()

  suspend fun start(firmwareUri: Uri) {
    _dfuProgress.emit(DfuProgress.Start)

    val firmwareFolder = unzipFirmware(firmwareUri)
    val activeConnection = activeConnectionUseCase.getConnectedDevice()
      ?: throw IllegalStateException("Can't start DFU without connected device")

    uploadFirmware(
      connection = activeConnection,
      firmwareFolder = firmwareFolder,
    )

    firmwareFolder.deleteRecursively()

    _dfuProgress.emit(null)
  }

  private suspend fun unzipFirmware(firmwareUri: Uri): File = withContext(dispatchers.io) {
    val tmpDir = File(context.cacheDir, "tmp_firmware")
      .also(File::deleteRecursively) // To make sure we don't have leftovers

    if (!tmpDir.mkdir()) {
      throw IllegalStateException("Can't create tmp folder for firmware")
    }
    context.contentResolver.openInputStream(firmwareUri)?.use { inputStream ->
      ZipInputStream(inputStream).use { zipStream ->
        zipStream.unzipAll(tmpDir)
      }
    }

    tmpDir
  }

  private suspend fun uploadFirmware(
    connection: BluetoothConnection,
    firmwareFolder: File,
  ) {
    require(firmwareFolder.isDirectory)

    withContext(dispatchers.default) {
      connection.perform {
        val firmwareFiles = firmwareFolder.listFiles()!!

        val fileDat = firmwareFiles.first { ".dat" in it.name }
        val fileBin = firmwareFiles.first { ".bin" in it.name }
        val fileBinSize = fileBin.length()

        val controlPointCharacteristic =
          findCharacteristic(UUID.fromString("00001531-1212-efde-1523-785feabcd123"))
        val packetCharacteristic =
          findCharacteristic(UUID.fromString("00001532-1212-efde-1523-785feabcd123"))

        checkNotNull(packetCharacteristic)
        checkNotNull(controlPointCharacteristic)

        controlPointCharacteristic.enableNotifications()

        _dfuProgress.emit(DfuProgress.Step1)

        controlPointCharacteristic.write(byteArrayOf(0x01, 0x04))

        _dfuProgress.emit(DfuProgress.Step2)

        val binFileSizeArray = ByteBuffer
          .allocate(4)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putInt(fileBinSize.toInt())
          .array()

        packetCharacteristic.write(ByteArray(8) + binFileSizeArray)
        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x01, 0x01))

        _dfuProgress.emit(DfuProgress.Step3)

        controlPointCharacteristic.write(byteArrayOf(0x02, 0x00))

        _dfuProgress.emit(DfuProgress.Step4)

        packetCharacteristic.write(fileDat.readBytes())
        controlPointCharacteristic.write(byteArrayOf(0x02, 0x01))
        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x02, 0x01))

        _dfuProgress.emit(DfuProgress.Step5)

        val confirmationNotificationsInterval = 0x64
        controlPointCharacteristic.write(
          byteArrayOf(
            0x08,
            confirmationNotificationsInterval.toByte()
          )
        )

        _dfuProgress.emit(DfuProgress.Step6)

        controlPointCharacteristic.write(byteArrayOf(0x03))

        _dfuProgress.emit(
          DfuProgress.Step7(
            sentBytes = 0L,
            firmwareSizeInBytes = fileBinSize,
          )
        )

        var sentBytesCount = 0L
        val firmwareSegment = ByteArray(DFU_SEGMENT_SIZE)
        var confirmationCountDown = confirmationNotificationsInterval

        FileInputStream(fileBin).use { fileStream ->
          var segmentBytesCount = fileStream.read(firmwareSegment)
          while (segmentBytesCount > 0) {
            packetCharacteristic.write(
              if (segmentBytesCount == firmwareSegment.size) {
                firmwareSegment
              } else {
                firmwareSegment.copyOfRange(0, segmentBytesCount)
              }
            )

            sentBytesCount += segmentBytesCount

            _dfuProgress.emit(
              DfuProgress.Step7(
                sentBytes = sentBytesCount,
                firmwareSizeInBytes = fileBinSize,
              )
            )

            if (sentBytesCount == fileBinSize) break

            if (--confirmationCountDown == 0) {
              confirmationCountDown = confirmationNotificationsInterval

              controlPointCharacteristic.awaitNotification(startsWith = 0x11)
            }

            segmentBytesCount = fileStream.read(firmwareSegment)
          }
        }

        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x03, 0x01))

        _dfuProgress.emit(DfuProgress.Step8)

        controlPointCharacteristic.write(byteArrayOf(0x04))
        controlPointCharacteristic.awaitNotification(byteArrayOf(0x10, 0x04, 0x01))

        _dfuProgress.emit(DfuProgress.Step9)

        try {
          controlPointCharacteristic.write(byteArrayOf(0x05))
        } catch (e: Exception) {
          Log.e("DFU", "Activation timeout") // Sometimes it's respond
        }

        _dfuProgress.emit(DfuProgress.Finalization)
      }
    }
  }

  companion object {
    private const val DFU_SEGMENT_SIZE = 20
  }
}
