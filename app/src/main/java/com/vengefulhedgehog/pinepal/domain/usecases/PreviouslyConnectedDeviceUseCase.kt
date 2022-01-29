package com.vengefulhedgehog.pinepal.domain.usecases

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// TODO probably should rename to BondedDeviceUseCase, after bonding implemented
class PreviouslyConnectedDeviceUseCase @Inject constructor(
  private val sharedPreferences: SharedPreferences,
) {

  var mac: String?
    get() = sharedPreferences.getString(KEY_CONNECTED_DEVICE_MAC, null)
    set(mac) {
      sharedPreferences.edit {
        putString(KEY_CONNECTED_DEVICE_MAC, mac)
      }
    }

  companion object {
    private const val KEY_CONNECTED_DEVICE_MAC = "connected_device_mac"
  }
}
