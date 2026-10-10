package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AppMode
import com.example.ui.CaptureViewModel
import com.example.ui.ModeSelectionScreen
import com.example.ui.RxScreen
import com.example.ui.TxScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val viewModel: CaptureViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()

        val permissionLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.RequestPermission()
        ) { _ -> }

        LaunchedEffect(currentMode) {
          if (currentMode == AppMode.TX) {
            val hasPermission = ContextCompat.checkSelfPermission(
              this@MainActivity,
              Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
              permissionLauncher.launch(Manifest.permission.CAMERA)
            }
          }
        }

        // Back button returns to role selection if inside TX or RX
        BackHandler(enabled = currentMode != AppMode.UNSELECTED) {
          viewModel.selectMode(AppMode.UNSELECTED)
        }

        Surface(modifier = Modifier.fillMaxSize()) {
          when (currentMode) {
            AppMode.UNSELECTED -> {
              ModeSelectionScreen(
                onSelectMode = { mode -> viewModel.selectMode(mode) }
              )
            }
            AppMode.TX -> {
              TxScreen(
                viewModel = viewModel,
                onChangeMode = { viewModel.selectMode(AppMode.UNSELECTED) }
              )
            }
            AppMode.RX -> {
              RxScreen(
                viewModel = viewModel,
                onChangeMode = { viewModel.selectMode(AppMode.UNSELECTED) }
              )
            }
          }
        }
      }
    }
  }
}
