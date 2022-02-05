package com.vengefulhedgehog.pinepal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vengefulhedgehog.pinepal.domain.handler.SystemEvent
import com.vengefulhedgehog.pinepal.ui.navigation.AppDirections
import com.vengefulhedgehog.pinepal.ui.screens.connected.ConnectedDeviceScreen
import com.vengefulhedgehog.pinepal.ui.screens.search.DeviceSearchScreen
import com.vengefulhedgehog.pinepal.ui.theme.PinePalTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.vengefulhedgehog.pinepal.MainActivityViewModel as ViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val viewModel by viewModels<ViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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
          val navController = rememberNavController()
          val navigationDirection by viewModel.navigationEvent
            .onEach { event ->
              when (event) {
                is SystemEvent.Navigation.To -> navController.navigate(event.direction)
                is SystemEvent.Navigation.Back -> {
                  if (event.direction.isNullOrBlank()) {
                    navController.popBackStack()
                  } else {
                    navController.popBackStack(event.direction, inclusive = false)
                  }
                }
              }
            }
            .collectAsState(initial = AppDirections.DEVICE_SEARCH)

          navController.popBackStack()

          NavHost(navController = navController, startDestination = AppDirections.DEVICE_SEARCH) {
            composable(AppDirections.DEVICE_SEARCH) { DeviceSearchScreen() }
            composable(AppDirections.CONNECTED_DEVICE) { ConnectedDeviceScreen() }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()

    viewModel.onPermissionCheckRequested()
  }
}
