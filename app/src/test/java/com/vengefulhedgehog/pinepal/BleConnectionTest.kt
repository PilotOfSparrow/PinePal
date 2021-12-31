package com.vengefulhedgehog.pinepal

import android.os.ParcelUuid
import androidx.test.core.app.ApplicationProvider
import com.vengefulhedgehog.pinepal.bluetooth.BluetoothConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.robolectric.RobolectricTest
import io.kotest.matchers.shouldNotBe
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBluetoothDevice
import java.util.*

@RobolectricTest
class BleConnectionTest : FunSpec() {

  init {
    coroutineDebugProbes = true

    test("findCharacteristic") {
      val robolectricBleDevice = ShadowBluetoothDevice.newInstance("00:11:22:33:AA:BB")
      val bleConnection = BluetoothConnection(
        context = ApplicationProvider.getApplicationContext(),
        device = robolectricBleDevice,
      )

      Shadows.shadowOf(robolectricBleDevice)
        .setUuids(arrayOf(ParcelUuid.fromString("00001531-1212-efde-1523-785feabcd123")))

      bleConnection.findCharacteristic(
        UUID.fromString("00001531-1212-efde-1523-785feabcd123")
      ) shouldNotBe null
    }
  }
}
