package com.example.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CaptureTelemetry
import com.example.model.LogEntry
import com.example.model.StreamPreset
import com.example.model.UsbDeviceInfo

/**
 * Direct Field Monitor / Local Viewer Screen.
 * Displays HDMI video and plays audio directly from the USB capture card
 * without transmitting or receiving over the network.
 */
@Composable
fun ViewerScreen(
    viewModel: CaptureViewModel,
    onChangeMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val usbDevices by viewModel.connectedUsbDevices.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val isLocalAudioEnabled by viewModel.isLocalAudioEnabled.collectAsStateWithLifecycle()
    val selectedPreset by viewModel.selectedPreset.collectAsStateWithLifecycle()

    val uvcDevice = usbDevices.firstOrNull { it.isUvcVideo } ?: usbDevices.firstOrNull()

    var isFullscreen by remember { mutableStateOf(false) }

    // Intercept back button when in fullscreen to return to Viewer dashboard
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    if (isFullscreen) {
        ViewerFullscreenView(
            telemetry = telemetry,
            isAudioEnabled = isLocalAudioEnabled,
            onToggleAudio = { enabled -> viewModel.setLocalAudioEnabled(enabled) },
            onExitFullscreen = { isFullscreen = false },
            onSurfaceReady = { holder -> viewModel.setViewerSurface(holder) },
            onSurfaceDestroyed = { viewModel.setViewerSurface(null) }
        )
    } else {
        ViewerDashboardView(
            telemetry = telemetry,
            uvcDevice = uvcDevice,
            logs = logs,
            isAudioEnabled = isLocalAudioEnabled,
            availablePresets = viewModel.availablePresets,
            selectedPreset = selectedPreset,
            onSelectPreset = { preset -> viewModel.selectPreset(preset) },
            onToggleAudio = { enabled -> viewModel.setLocalAudioEnabled(enabled) },
            onEnterFullscreen = { isFullscreen = true },
            onRequestUsbPermission = { vid, pid -> viewModel.requestUsbPermission(vid, pid) },
            onRefresh = { viewModel.refreshAll() },
            onClearLogs = { viewModel.clearLogs() },
            onChangeMode = onChangeMode,
            onSurfaceReady = { holder -> viewModel.setViewerSurface(holder) },
            onSurfaceDestroyed = { viewModel.setViewerSurface(null) },
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerDashboardView(
    telemetry: CaptureTelemetry,
    uvcDevice: UsbDeviceInfo?,
    logs: List<LogEntry>,
    isAudioEnabled: Boolean,
    availablePresets: List<StreamPreset>,
    selectedPreset: StreamPreset,
    onSelectPreset: (StreamPreset) -> Unit,
    onToggleAudio: (Boolean) -> Unit,
    onEnterFullscreen: () -> Unit,
    onRequestUsbPermission: (Int, Int) -> Unit,
    onRefresh: () -> Unit,
    onClearLogs: () -> Unit,
    onChangeMode: () -> Unit,
    onSurfaceReady: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Stream Relay Viewer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Direct HDMI Field Monitor · No Streaming",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onChangeMode) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Switch Mode"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Devices"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Video Card
            item {
                ViewerVideoCard(
                    telemetry = telemetry,
                    onEnterFullscreen = onEnterFullscreen,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceDestroyed = onSurfaceDestroyed
                )
            }

            // Quick Controls Card
            item {
                ViewerControlsCard(
                    isAudioEnabled = isAudioEnabled,
                    availablePresets = availablePresets,
                    selectedPreset = selectedPreset,
                    onToggleAudio = onToggleAudio,
                    onSelectPreset = onSelectPreset,
                    onEnterFullscreen = onEnterFullscreen
                )
            }

            // Telemetry & Hardware Status Card
            item {
                ViewerTelemetryCard(
                    telemetry = telemetry,
                    uvcDevice = uvcDevice,
                    onRequestUsbPermission = onRequestUsbPermission
                )
            }

            // Hardware Diagnostic Logs
            item {
                ViewerLogsCard(
                    logs = logs,
                    onClearLogs = onClearLogs
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ViewerVideoCard(
    telemetry: CaptureTelemetry,
    onEnterFullscreen: () -> Unit,
    onSurfaceReady: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Direct Video Monitor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onEnterFullscreen,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Full Screen",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Full Screen")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onSurfaceReady(holder)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    onSurfaceReady(holder)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onSurfaceDestroyed()
                                }
                            })
                        }
                    }
                )

                // Status chip top-left
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (telemetry.isStreaming) Color(0xFF10B981) else Color(0xFFEF4444)
                        ) {
                            Box(modifier = Modifier.size(8.dp))
                        }
                        Text(
                            text = if (telemetry.isStreaming) "DIRECT HDMI" else "NO SIGNAL",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Floating Expand Button top-right
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White
                ) {
                    IconButton(
                        onClick = onEnterFullscreen,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Go Full Screen",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // FPS & Resolution badge bottom-left
                if (telemetry.isStreaming) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = "${telemetry.fps} FPS · ${telemetry.resolutionWidth}x${telemetry.resolutionHeight}",
                            color = Color(0xFF38BDF8),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerControlsCard(
    isAudioEnabled: Boolean,
    availablePresets: List<StreamPreset>,
    selectedPreset: StreamPreset,
    onToggleAudio: (Boolean) -> Unit,
    onSelectPreset: (StreamPreset) -> Unit,
    onEnterFullscreen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Monitor Controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Primary Fullscreen Action
            Button(
                onClick = onEnterFullscreen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Fullscreen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Full Screen Display", fontWeight = FontWeight.Bold)
            }

            // Speaker Toggle
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isAudioEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = null,
                            tint = if (isAudioEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isAudioEnabled) "Phone Speaker: Active" else "Phone Speaker: Muted",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Plays audio directly from HDMI capture card",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FilledTonalButton(onClick = { onToggleAudio(!isAudioEnabled) }) {
                        Text(if (isAudioEnabled) "Mute" else "Unmute")
                    }
                }
            }

            // Capture Resolution Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Capture Resolution Preset:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availablePresets.forEach { preset ->
                        val isSelected = preset.id == selectedPreset.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectPreset(preset) },
                            label = { Text(preset.label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerTelemetryCard(
    telemetry: CaptureTelemetry,
    uvcDevice: UsbDeviceInfo?,
    onRequestUsbPermission: (Int, Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cable,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hardware Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = if (telemetry.isStreaming) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF64748B).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (telemetry.isStreaming) "ACTIVE" else "STANDBY",
                        color = if (telemetry.isStreaming) Color(0xFF10B981) else Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (uvcDevice != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = uvcDevice.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "USB VID: ${Integer.toHexString(uvcDevice.vendorId)}  PID: ${Integer.toHexString(uvcDevice.productId)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!uvcDevice.hasPermission) {
                            Button(
                                onClick = { onRequestUsbPermission(uvcDevice.vendorId, uvcDevice.productId) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                            ) {
                                Text("Grant Permission", fontSize = 11.sp)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ready", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Telemetry grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("FPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${telemetry.fps}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Resolution", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${telemetry.resolutionWidth}x${telemetry.resolutionHeight}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Format", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(telemetry.bufferFormat.ifEmpty { "MJPEG" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerLogsCard(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hardware Logs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClearLogs, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear logs")
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    if (logs.isEmpty()) {
                        item {
                            Text(
                                text = "No logs yet.",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        items(logs.take(30)) { log ->
                            Text(
                                text = "${log.timestamp} [${log.tag}] ${log.message}",
                                color = if (log.isError) Color(0xFFF87171) else Color(0xFF38BDF8),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Fullscreen Video Monitor for Viewer mode.
 * Features:
 * - Immersive edge-to-edge display with keep-screen-on.
 * - Single-tap gesture to toggle control overlay.
 * - Aspect ratio toggle: Fit (letterboxed 16:9) vs Fill (stretch/crop to screen edges).
 * - Screen rotation toggle: Landscape vs Sensor/Portrait.
 * - Speaker audio toggle on/off.
 * - Back button or exit button cleanly returns to Viewer dashboard.
 */
@Composable
private fun ViewerFullscreenView(
    telemetry: CaptureTelemetry,
    isAudioEnabled: Boolean,
    onToggleAudio: (Boolean) -> Unit,
    onExitFullscreen: () -> Unit,
    onSurfaceReady: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showOverlay by remember { mutableStateOf(true) }
    var isForcedLandscape by remember { mutableStateOf(false) }
    var isFillMode by remember { mutableStateOf(false) }

    // Keep screen on while in fullscreen preview
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Handle landscape orientation toggle
    DisposableEffect(isForcedLandscape) {
        if (isForcedLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showOverlay = !showOverlay
            }
    ) {
        // Video presentation
        if (isFillMode) {
            // Fill mode: Stretch to fill the whole screen
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceReady(holder)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                onSurfaceReady(holder)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        })
                    }
                }
            )
        } else {
            // Fit mode: Preserves native 16:9 aspect ratio
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val containerAspect = maxWidth / maxHeight
                val videoAspect = if (telemetry.resolutionWidth > 0 && telemetry.resolutionHeight > 0) {
                    telemetry.resolutionWidth.toFloat() / telemetry.resolutionHeight.toFloat()
                } else {
                    16f / 9f
                }

                val videoModifier = if (containerAspect > videoAspect) {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(videoAspect)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoAspect)
                }

                AndroidView(
                    modifier = videoModifier,
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onSurfaceReady(holder)
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    onSurfaceReady(holder)
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onSurfaceDestroyed()
                                }
                            })
                        }
                    }
                )
            }
        }

        // Animated HUD Controls Overlay
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(16.dp)
            ) {
                // Top Bar with back/exit, status badges, and aspect ratio toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ) {
                            IconButton(onClick = onExitFullscreen) {
                                Icon(
                                    imageVector = Icons.Default.FullscreenExit,
                                    contentDescription = "Exit Full Screen",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "Viewer Fullscreen",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Direct HDMI · Tap to toggle controls",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // FPS Pill
                        Surface(
                            color = if (telemetry.isStreaming && telemetry.fps > 0f) Color(0xFF10B981).copy(alpha = 0.25f) else Color(0xFF64748B).copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (telemetry.isStreaming && telemetry.fps > 0f) Color(0xFF10B981) else Color(0xFF64748B))
                        ) {
                            Text(
                                text = if (telemetry.isStreaming) "${telemetry.fps} FPS" else "0 FPS",
                                color = if (telemetry.isStreaming && telemetry.fps > 0f) Color(0xFF34D399) else Color(0xFF94A3B8),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Aspect Ratio Toggle
                        Surface(
                            shape = CircleShape,
                            color = if (isFillMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ) {
                            IconButton(onClick = { isFillMode = !isFillMode }) {
                                Icon(
                                    imageVector = Icons.Default.AspectRatio,
                                    contentDescription = "Toggle Fit / Fill Mode",
                                    tint = if (isFillMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Orientation Rotation Button
                        Surface(
                            shape = CircleShape,
                            color = if (isForcedLandscape) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ) {
                            IconButton(onClick = { isForcedLandscape = !isForcedLandscape }) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "Toggle Landscape Rotation",
                                    tint = if (isForcedLandscape) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Bottom Floating Control Bar
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Speaker Toggle
                        FilledTonalButton(
                            onClick = { onToggleAudio(!isAudioEnabled) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isAudioEnabled) MaterialTheme.colorScheme.primaryContainer else Color(0xFF334155)
                            )
                        ) {
                            Icon(
                                imageVector = if (isAudioEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isAudioEnabled) "Speaker: Active" else "Speaker: Muted")
                        }

                        // Aspect Ratio Label
                        FilledTonalButton(
                            onClick = { isFillMode = !isFillMode },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isFillMode) MaterialTheme.colorScheme.secondaryContainer else Color(0xFF334155)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isFillMode) "Mode: Fill Screen" else "Mode: Fit 16:9")
                        }

                        // Exit button
                        OutlinedButton(
                            onClick = onExitFullscreen,
                            border = BorderStroke(1.dp, Color(0xFF64748B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Exit Full Screen", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
