package com.vengefulhedgehog.pinepal.extensions

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun Context.permissionGranted(permission: String): Boolean =
  ContextCompat.checkSelfPermission(
    this,
    permission
  ) == PackageManager.PERMISSION_GRANTED
