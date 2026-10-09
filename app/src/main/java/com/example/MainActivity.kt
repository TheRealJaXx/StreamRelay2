package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.CaptureScreen
import com.example.ui.CaptureViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val viewModel: CaptureViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        val permissionLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.RequestPermission()
        ) { _ -> }

        LaunchedEffect(Unit) {
          val hasPermission = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.CAMERA
          ) == PackageManager.PERMISSION_GRANTED

          if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
          }
        }

        Surface(modifier = Modifier.fillMaxSize()) {
          CaptureScreen(viewModel = viewModel)
        }
      }
    }
  }
}
