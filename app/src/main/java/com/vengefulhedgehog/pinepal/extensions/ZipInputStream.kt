package com.vengefulhedgehog.pinepal.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

suspend fun ZipInputStream.unzipAll(directory: File) {
  require(directory.isDirectory)

  val zipStream = this

  withContext(Dispatchers.IO) {
    var entry = zipStream.nextEntry
    val outputBuffer = ByteArray(1024 * 500)

    while (entry != null) {
      FileOutputStream(File(directory, entry.name)).use { fileOutput ->
        var len = zipStream.read(outputBuffer)
        while (len > 0) {
          fileOutput.write(outputBuffer, 0, len)

          len = zipStream.read(outputBuffer)
        }
      }

      zipStream.closeEntry()

      entry = zipStream.nextEntry
    }

    zipStream.closeEntry()
  }
}
