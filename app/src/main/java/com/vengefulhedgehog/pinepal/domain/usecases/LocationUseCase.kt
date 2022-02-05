package com.vengefulhedgehog.pinepal.domain.usecases

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import com.vengefulhedgehog.pinepal.extensions.permissionGranted
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationUseCase @Inject constructor(
  @ApplicationContext private val context: Context,
) {

  private val _locationServiceActive = MutableStateFlow(false)
  val locationServiceActive = _locationServiceActive.asStateFlow()

  private val _locationPermissionGranted = MutableStateFlow(false)
  val locationPermissionGranted = _locationPermissionGranted.asStateFlow()

  private val locationManager by lazy {
    context.getSystemService(LocationManager::class.java)
  }

  private val locationBroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      checkAvailability()
    }
  }

  init {
    context.registerReceiver(
      locationBroadcastReceiver,
      IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
    )
  }

  fun checkAvailability() {
    _locationServiceActive.tryEmit(
      LocationManagerCompat.isLocationEnabled(locationManager)
    )
    _locationPermissionGranted.tryEmit(
      context.permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    )
  }
}
