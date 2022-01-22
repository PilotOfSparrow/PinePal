package com.vengefulhedgehog.pinepal.domain.bluetooth

sealed class DfuProgress(val description: String) {
  object Start : DfuProgress("Starting DFU")

  object Step1 : DfuProgress("Initializing firmware update")

  object Step2 : DfuProgress("Sending firmware size")

  object Step3 : DfuProgress("Preparing to send dat file")

  object Step4 : DfuProgress("Sending dat file")

  object Step5 : DfuProgress("Negotiate confirmation intervals")

  object Step6 : DfuProgress("Preparing to send firmware")

  data class Step7(
    val sentBytes: Long,
    val firmwareSizeInBytes: Long,
  ) : DfuProgress("Sending firmware")

  object Step8 : DfuProgress("Received image validation")

  object Step9 : DfuProgress("Activate new firmware")

  object Finalization : DfuProgress("Finalizing DFU")
}
