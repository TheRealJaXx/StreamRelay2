package com.example

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.LogEntry
import com.example.ui.CaptureViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            val viewModel: CaptureViewModel = viewModel()
            CaptureScreen(viewModel = viewModel)
        }
    }
}

@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val connectedDevices by viewModel.connectedUsbDevices.collectAsStateWithLifecycle()
    val selectedPreset by viewModel.selectedPreset.collectAsStateWithLifecycle()
    val outputDeviceType by viewModel.audioOutputDeviceType.collectAsStateWithLifecycle()
    val volume by viewModel.audioVolume.collectAsStateWithLifecycle()
    val isMuted by viewModel.audioMuted.collectAsStateWithLifecycle()

    val usbDevice = connectedDevices.firstOrNull { it.isUvcVideo } ?: connectedDevices.firstOrNull()
    val isDeviceConnected = usbDevice != null
    val hasPermission = usbDevice?.hasPermission == true
    val screenAspect = if (telemetry.resolutionWidth > 0 && telemetry.resolutionHeight > 0) {
        telemetry.resolutionWidth.toFloat() / telemetry.resolutionHeight.toFloat()
    } else {
        16f / 9f
    }

    var overlayVisible by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            delay(4000)
            overlayVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    overlayVisible = !overlayVisible
                }
            }
    ) {
        if (isDeviceConnected && hasPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(screenAspect),
                    factory = { ctx ->
                        android.view.SurfaceView(ctx).apply {
                            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                    viewModel.setPreviewSurface(holder)
                                }

                                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                                    viewModel.setPreviewSurface(holder)
                                }

                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                    viewModel.setPreviewSurface(null)
                                }
                            })
                        }
                    }
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF090B10)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = Color(0xFF8FA7C7),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Connect USB capture card",
                        color = Color.White,
                        fontSize = 28.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (usbDevice != null) "USB permission is required before capture can start." else "Plug in the HDMI capture card and grant permission.",
                        color = Color(0xFFB7C5D8),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 28.dp)
                    )
                    if (usbDevice != null && !hasPermission) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = {
                                if (usbDevice != null) {
                                    viewModel.requestUsbPermission(usbDevice.vendorId, usbDevice.productId)
                                }
                            }
                        ) {
                            Text("Grant USB Permission")
                        }
                    }
                }
            }
        }

        if (overlayVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.TopEnd
            ) {
                Card(
                    modifier = Modifier
                        .padding(18.dp)
                        .fillMaxWidth(0.92f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF171C22)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Capture monitor",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                            TextButton(onClick = { activity?.finish() }) {
                                Text("Back")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Resolution",
                            color = Color(0xFFB6C6DE),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(480 to "480p", 720 to "720p", 1080 to "1080p").forEach { (res, label) ->
                                val selected = selectedPreset.width == res
                                Button(
                                    onClick = { viewModel.selectPreset(res) },
                                    modifier = Modifier.weight(1f),
                                    enabled = true,
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = if (selected) Color(0xFF4C8BF5) else Color(0xFF2B3844)
                                    )
                                ) {
                                    Text(label)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isMuted) "Muted" else "Volume",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Slider(
                                value = if (isMuted) 0f else volume,
                                onValueChange = { value -> viewModel.setVolume(value) },
                                modifier = Modifier.weight(1f),
                                valueRange = 0f..1f
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        val audioNote = if (outputDeviceType == "bluetooth") {
                            "Bluetooth adds delay outside the app's control. Use wired audio for the lowest latency."
                        } else {
                            ""
                        }
                        if (audioNote.isNotEmpty()) {
                            Text(
                                text = audioNote,
                                color = Color(0xFFFECC6A),
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Stats",
                            color = Color(0xFFB6C6DE),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            val format = telemetry.bufferFormat.ifBlank { "MJPEG" }
                            Text("Resolution: ${if (telemetry.resolutionWidth > 0) "${telemetry.resolutionWidth}x${telemetry.resolutionHeight}" else "Waiting"}", color = Color.White, fontSize = 13.sp)
                            Text("FPS: ${if (telemetry.isStreaming) telemetry.fps else "0.0"}", color = Color.White, fontSize = 13.sp)
                            Text("Frame size: ${if (telemetry.lastFrameSizeBytes > 0) "${telemetry.lastFrameSizeBytes / 1024} KB" else "--"}", color = Color.White, fontSize = 13.sp)
                            Text("Audio output: ${outputDeviceType.replaceFirstChar { it.uppercase() }}", color = Color.White, fontSize = 13.sp)
                            Text("Format: $format", color = Color.White, fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { showLogs = true }) {
                                Text("Logs")
                            }
                            TextButton(onClick = { overlayVisible = false }) {
                                Text("Hide")
                            }
                        }
                    }
                }
            }
        }

        if (showLogs) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxSize(0.85f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101820))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Logs", color = Color.White, fontSize = 20.sp)
                            TextButton(onClick = { showLogs = false }) {
                                Text("Close")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            color = Color(0xFF0D1722),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (logs.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No log events yet", color = Color(0xFF8EA3B7), fontSize = 14.sp)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp)
                                ) {
                                    items(logs) { log ->
                                        LogRow(log = log)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = log.timestamp,
            color = Color(0xFF8EA3B7),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "[${log.tag}]",
            color = if (log.isError) Color(0xFFEF6C6C) else Color(0xFF7BC7FF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = log.message,
            color = if (log.isError) Color(0xFFFFD0D0) else Color(0xFFEAF3FF),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
