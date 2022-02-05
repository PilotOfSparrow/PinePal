package com.vengefulhedgehog.pinepal.ui.screens.connected

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vengefulhedgehog.pinepal.domain.model.bluetooth.DfuProgress

@Composable
fun ConnectedDeviceScreen() {
  val viewModel = hiltViewModel<ConnectedDeviceViewModel>()
  val state by viewModel.state.collectAsState()

  val firmawareSelectionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = viewModel::onFirmwareSelect
  )

  BackHandler(
    enabled = state.dfuProgress != null || state.reconnection,
    onBack = viewModel::onDeviceDisconnect
  )

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
          text = state.deviceName,
        )
        Text(
          text = state.deviceAddress,
          modifier = Modifier.padding(top = 4.dp)
        )
      }
      if (state.dfuProgress == null && !state.reconnection) {
        Button(onClick = viewModel::onDeviceDisconnect) {
          Text(text = "Disconnect")
        }
      }
    }
    if (state.dfuProgress == null && !state.reconnection) {
      Text(
        text = "Firmware version ${state.firmwareVersion}",
        modifier = Modifier.padding(top = 8.dp)
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = "Notifications access status")

        if (state.notificationAccessGranted) {
          Text(text = "Granted")
        } else {
          val context = LocalContext.current
          Button(
            modifier = Modifier.padding(12.dp),
            onClick = {
              context.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
              )
            }
          ) {
            Text(text = "Grant")
          }
        }
      }
      Button(
        onClick = viewModel::onTimeSync,
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 20.dp)
      ) {
        Text(text = "Sync time")
      }
      Button(
        onClick = { firmawareSelectionLauncher.launch(arrayOf("application/zip")) },
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.CenterHorizontally)
          .padding(top = 20.dp)
      ) {
        Text(text = "Start Firmware Update")
      }
    } else {
      val progress = state.dfuProgress
      if (progress is DfuProgress.Step7) {
        Text(
          text = progress.run { "$sentBytes / $firmwareSizeInBytes" },
          modifier = Modifier
            .padding(top = 20.dp)
            .align(Alignment.CenterHorizontally)
        )
        LinearProgressIndicator(
          progress = progress.run { sentBytes.toFloat() / firmwareSizeInBytes },
          color = Color.Magenta,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
        )
      } else {
        Text(
          text = progress?.description ?: "Attempting to reconnect",
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
