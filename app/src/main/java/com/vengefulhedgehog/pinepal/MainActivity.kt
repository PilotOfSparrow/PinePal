package com.vengefulhedgehog.pinepal

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
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
import androidx.lifecycle.lifecycleScope
import com.vengefulhedgehog.pinepal.domain.model.bluetooth.DfuProgress
import com.vengefulhedgehog.pinepal.ui.theme.BackgroundDark
import com.vengefulhedgehog.pinepal.ui.theme.PinePalTheme
import com.vengefulhedgehog.pinepal.ui.theme.Purple500
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.vengefulhedgehog.pinepal.MainActivityViewModel as ViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val viewModel by viewModels<ViewModel>()

  private val resultLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) {
    viewModel.onPermissionCheckRequested()
  }
  private val activityResultLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    viewModel.onPermissionCheckRequested()
  }
  private val firmwareSelectionLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { fileUri ->
    viewModel.onFirmwareSelected(fileUri)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    viewModel
      .firmwareSelectionRequest
      .onEach { firmwareSelectionLauncher.launch(arrayOf("application/zip")) }
      .launchIn(lifecycleScope)

    viewModel
      .windowFlagAdded
      .onEach { window.addFlags(it) }
      .launchIn(lifecycleScope)

    viewModel
      .windowFlagRemoved
      .onEach { window.clearFlags(it) }
      .launchIn(lifecycleScope)

    setContent {
      PinePalTheme {
        Surface(color = Color.Black) {
          val screenState by viewModel.state.collectAsState()

          when (screenState) {
            ViewState.Loading -> {}

            is ViewState.ConnectedDevice -> {
              ConnectedDevice(screenState as ViewState.ConnectedDevice)
            }
            is ViewState.DevicesDiscovery -> {
              DevicesDiscovery(screenState as ViewState.DevicesDiscovery)
            }

            ViewState.PermissionsRequired,
            ViewState.ServicesRequired.Location,
            ViewState.ServicesRequired.Bluetooth -> {
              DiscoverySetup(
                description = screenState.getDiscoveryDescription(),
                buttonText = screenState.getDiscoveryButtonText(),
                buttonAction = screenState.getDiscoveryButtonAction(),
              )
            }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()

    viewModel.onPermissionCheckRequested()
  }

  @Composable
  private fun DiscoverySetup(
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
  private fun ConnectedDevice(
    deviceInfo: ViewState.ConnectedDevice,
  ) {
    Column(
      modifier = Modifier
        .padding(start = 16.dp, end = 16.dp)
        .fillMaxWidth()
        .fillMaxHeight()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column {
          Text(
            text = deviceInfo.name,
          )
          Text(
            text = deviceInfo.address,
            modifier = Modifier.padding(top = 4.dp)
          )
        }
        if (deviceInfo.dfuProgress == null) {
          Button(onClick = viewModel::onDeviceDisconnectionRequested) {
            Text(text = "Disconnect")
          }
        }
      }
      if (deviceInfo.dfuProgress == null) {
        Text(
          text = "Firmware version ${deviceInfo.firmwareVersion}",
          modifier = Modifier.padding(top = 8.dp)
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(text = "Notifications access status")

          if (deviceInfo.notificationAccessGranted) {
            Text(text = "Granted")
          } else {
            Button(
              modifier = Modifier.padding(12.dp),
              onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }) {
              Text(text = "Grant")
            }
          }
        }
        Button(
          onClick = { viewModel.onTimeSyncRequested() },
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
        ) {
          Text(text = "Sync time")
        }
        Button(
          onClick = { viewModel.onDfuRequested() },
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.CenterHorizontally)
            .padding(top = 20.dp)
        ) {
          Text(text = "Start Firmware Update")
        }
      } else {
        if (deviceInfo.dfuProgress is DfuProgress.Step7) {
          Text(
            text = deviceInfo.dfuProgress.run { "$sentBytes / $firmwareSizeInBytes" },
            modifier = Modifier
              .padding(top = 20.dp)
              .align(Alignment.CenterHorizontally)
          )
          LinearProgressIndicator(
            progress = deviceInfo.dfuProgress.run { sentBytes.toFloat() / firmwareSizeInBytes },
            color = Color.Magenta,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 20.dp)
          )
        } else {
          Text(
            text = deviceInfo.dfuProgress.description,
            modifier = Modifier
              .padding(top = 20.dp)
              .align(Alignment.CenterHorizontally)
          )
          LinearProgressIndicator(
            color = Color.Magenta,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 20.dp)
          )
        }
      }
    }
  }

  @Composable
  private fun DevicesDiscovery(discoveryInfo: ViewState.DevicesDiscovery) {
    Column(modifier = Modifier.fillMaxSize()) {
      ConstraintLayout(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 20.dp)
      ) {
        val (title, buttonRefresh) = createRefs()
        val text = if (discoveryInfo.postDfuReconnectionAttempt) {
          "Attempting to reconnect"
        } else {
          "Discovering devices"
        }

        Text(
          text = text,
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

        val shouldShowProgress = discoveryInfo.run {
          discoveryInProgress || postDfuReconnectionAttempt
        }
        if (shouldShowProgress) {
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
              .clickable { viewModel.onDeviceScanRequested() }
              .constrainAs(buttonRefresh) {
                top.linkTo(parent.top)
                end.linkTo(parent.end, margin = 16.dp)
                bottom.linkTo(parent.bottom)
              }
          )
        }
      }
      if (!discoveryInfo.postDfuReconnectionAttempt) {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
        ) {
          items(discoveryInfo.devices, key = { it.address }) { device ->
            Column(
              modifier = Modifier
                .clickable { viewModel.onDeviceSelected(device) }
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
  }

  private fun ViewState.getDiscoveryDescription(): String = when (this) {
    ViewState.PermissionsRequired -> "Multiple permissions required for device searching"
    ViewState.ServicesRequired.Location -> "Location not enabled"
    ViewState.ServicesRequired.Bluetooth -> "Bluetooth not enabled"
    else -> throw IllegalStateException("No discovery description available for $this")
  }

  private fun ViewState.getDiscoveryButtonText(): String = when (this) {
    ViewState.PermissionsRequired -> "Grant"

    ViewState.ServicesRequired.Location,
    ViewState.ServicesRequired.Bluetooth -> "Enable"

    else -> throw IllegalStateException("No discovery button text available for $this")
  }

  private fun ViewState.getDiscoveryButtonAction(): () -> Unit = when (this) {
    ViewState.PermissionsRequired -> {
      {
        // TODO Handle "Never ask again" situation
        resultLauncher.launch(
          arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
          )
        )
      }
    }
    ViewState.ServicesRequired.Location -> {
      {
        activityResultLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
      }
    }
    ViewState.ServicesRequired.Bluetooth -> {
      {
        activityResultLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
      }
    }

    else -> throw IllegalStateException("No discovery button action available for $this")
  }

  sealed interface ViewState {

    object Loading : ViewState

    object PermissionsRequired : ViewState

    sealed interface ServicesRequired : ViewState {
      object Location : ServicesRequired

      object Bluetooth : ServicesRequired
    }

    data class DevicesDiscovery(
      val devices: List<BluetoothDevice>,
      val discoveryInProgress: Boolean,
      val postDfuReconnectionAttempt: Boolean = false,
    ) : ViewState

    data class ConnectedDevice(
      val name: String,
      val address: String,
      val firmwareVersion: String,
      val dfuProgress: DfuProgress? = null,
      val notificationAccessGranted: Boolean = false,
    ) : ViewState
  }
}
