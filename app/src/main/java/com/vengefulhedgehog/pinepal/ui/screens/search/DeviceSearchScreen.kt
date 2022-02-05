package com.vengefulhedgehog.pinepal.ui.screens.search

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.hilt.navigation.compose.hiltViewModel
import com.vengefulhedgehog.pinepal.domain.model.bluetooth.BleDevice
import com.vengefulhedgehog.pinepal.ui.theme.BackgroundDark
import com.vengefulhedgehog.pinepal.ui.theme.Purple500

@Composable
fun DeviceSearchScreen() {
  val viewModel = hiltViewModel<DeviceSearchViewModel>()
  val state by viewModel.state.collectAsState()

  when {
    !state.locationPermissionGranted -> LocationPermissionSetup(viewModel)
    !state.locationServiceActive -> LocationServiceSetup()
    !state.bluetoothServiceActive -> BluetoothServiceSetup()
    else -> Search(state = state, viewModel = viewModel)
  }
}

@Composable
private fun LocationPermissionSetup(viewModel: DeviceSearchViewModel) {
  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
    onResult = { viewModel.onLocationPermissionResult() }
  )
  SearchSetup(
    description = "Location permission required for device searching",
    buttonText = "Grant",
    buttonAction = {
      // TODO Handle "Never ask again" situation
      permissionLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT
        )
      )
    }
  )
}

@Composable
private fun LocationServiceSetup() {
  val activityResultLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
    onResult = {}
  )
  SearchSetup(
    description = "Location not enabled",
    buttonText = "Enable",
    buttonAction = {
      activityResultLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
  )
}

@Composable
private fun BluetoothServiceSetup() {
  val activityResultLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
    onResult = {}
  )
  SearchSetup(
    description = "Bluetooth not enabled",
    buttonText = "Enable",
    buttonAction = {
      activityResultLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
  )
}

@Composable
private fun SearchSetup(
  description: String,
  buttonText: String,
  buttonAction: () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
  ) {
    Text(
      text = description,
      textAlign = TextAlign.Center,
    )
    Button(
      modifier = Modifier.padding(top = 8.dp),
      onClick = buttonAction
    ) {
      Text(text = buttonText)
    }
  }
}

@Composable
private fun Search(state: DeviceSearchState, viewModel: DeviceSearchViewModel) {
  Column(modifier = Modifier.fillMaxSize()) {
    ConstraintLayout(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 20.dp)
    ) {
      val (title, buttonRefresh) = createRefs()

      Text(
        text = "Discovering devices",
        modifier = Modifier.constrainAs(title) {
          width = Dimension.preferredWrapContent

          linkTo(
            start = parent.start,
            top = parent.top,
            end = buttonRefresh.start,
            bottom = parent.bottom,
            startMargin = 16.dp,
            horizontalBias = 0F,
          )
        }
      )

      if (state.searchInProgress) {
        CircularProgressIndicator(
          modifier = Modifier.constrainAs(buttonRefresh) {
            top.linkTo(parent.top)
            end.linkTo(parent.end, margin = 16.dp)
            bottom.linkTo(parent.bottom)
          }
        )
      } else {
        Image(
          painter = painterResource(id = android.R.drawable.stat_notify_sync),
          contentDescription = "",
          modifier = Modifier
            .clickable { viewModel.onDeviceScanRequest() }
            .constrainAs(buttonRefresh) {
              top.linkTo(parent.top)
              end.linkTo(parent.end, margin = 16.dp)
              bottom.linkTo(parent.bottom)
            }
        )
      }
    }
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .background(BackgroundDark)
    ) {
      items(state.findings, key = BleDevice::address) { device ->
        Column(
          modifier = Modifier
            .clickable { viewModel.onDeviceSelect(device.address) }
            .fillMaxWidth()
            .background(Purple500)
            .padding(20.dp)
        ) {
          Text(
            text = device.name,
            modifier = Modifier.fillMaxWidth()
          )
          Text(text = device.address, color = Color.LightGray, fontSize = 12.sp)
        }
      }
    }
  }
}
