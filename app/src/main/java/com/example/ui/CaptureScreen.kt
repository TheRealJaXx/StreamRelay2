package com.example.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CaptureTelemetry
import com.example.model.LogEntry
import com.example.model.StreamPreset
import com.example.model.UsbDeviceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier
) {
    val usbDevices by viewModel.connectedUsbDevices.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val selectedPreset by viewModel.selectedPreset.collectAsStateWithLifecycle()

    val uvcDevice = usbDevices.firstOrNull { it.isUvcVideo } ?: usbDevices.firstOrNull()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Stream Relay",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Milestone 1 · Hardware Video Link",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshAll() },
                        modifier = Modifier.testTag("refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh devices"
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
            // Milestone Brief Header Card
            item {
                MilestoneHeaderCard()
            }

            // USB Capture Device Card
            item {
                UsbDeviceStatusCard(
                    usbDevice = uvcDevice,
                    onRequestUsbPermission = { vid, pid ->
                        viewModel.requestUsbPermission(vid, pid)
                    }
                )
            }

            // Format & Resolution Selector Card
            item {
                StreamPresetSelectorCard(
                    presets = viewModel.availablePresets,
                    selectedPreset = selectedPreset,
                    onSelectPreset = { viewModel.selectPreset(it) }
                )
            }

            // Dedicated USB Video Preview & Capture Controls
            item {
                UsbVideoPreviewCard(
                    telemetry = telemetry,
                    isDeviceReady = uvcDevice != null && uvcDevice.hasPermission,
                    onSurfaceReady = { surface -> viewModel.setPreviewSurface(surface) },
                    onSurfaceDestroyed = { viewModel.setPreviewSurface(null) },
                    onStartCapture = { viewModel.startCapture() },
                    onStopCapture = { viewModel.stopCapture() }
                )
            }

            // Telemetry Verification HUD
            item {
                TelemetryHudCard(telemetry = telemetry)
            }

            // Diagnostic Log Section
            item {
                DiagnosticLogsSection(
                    logs = logs,
                    onClearLogs = { viewModel.clearLogs() }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun MilestoneHeaderCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cable,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "PS3 HDMI → USB-C Ingestion",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Direct hardware capture from MacroSilicon UVC card",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UsbDeviceStatusCard(
    usbDevice: UsbDeviceInfo?,
    onRequestUsbPermission: (vendorId: Int, productId: Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("usb_status_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (usbDevice?.hasPermission == true) Color(0xFF10B981) else MaterialTheme.colorScheme.outlineVariant
        )
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
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = if (usbDevice != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "USB Capture Device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (usbDevice != null) {
                    val isReady = usbDevice.hasPermission
                    val badgeColor = if (isReady) Color(0xFF10B981) else Color(0xFFF59E0B)
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isReady) "Ready to Stream" else "Permission Required",
                            color = badgeColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "No USB Device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (usbDevice != null) {
                Text(
                    text = usbDevice.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Hardware ID: ${usbDevice.formattedVidPid} · ${usbDevice.interfaceCount} interfaces",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (usbDevice.interfacesSummary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = usbDevice.interfacesSummary.take(2).joinToString(" | "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!usbDevice.hasPermission) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { onRequestUsbPermission(usbDevice.vendorId, usbDevice.productId) },
                        modifier = Modifier.testTag("grant_usb_permission_button")
                    ) {
                        Text("Grant USB Host Permission")
                    }
                }
            } else {
                Text(
                    text = "Plug your HDMI capture card into the phone's USB-C port.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamPresetSelectorCard(
    presets: List<StreamPreset>,
    selectedPreset: StreamPreset,
    onSelectPreset: (StreamPreset) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stream_preset_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Capture Resolution & Format",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "MJPEG compresses over USB 2.0 to unlock 60 FPS (YUV uncompressed is hardware-capped at ~6 FPS).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = preset.id == selectedPreset.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        label = {
                            Text(
                                text = preset.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (preset.isMjpeg) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = selectedPreset.description,
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedPreset.isMjpeg) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun UsbVideoPreviewCard(
    telemetry: CaptureTelemetry,
    isDeviceReady: Boolean,
    onSurfaceReady: (Any) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("video_preview_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                        text = "USB Capture Preview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (telemetry.isStreaming) {
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "USB FEED ACTIVE",
                                color = Color(0xFF10B981),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // SurfaceView container dedicated exclusively to USB device
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D1B2A))
                    .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(12.dp)),
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

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    onSurfaceReady(holder)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onSurfaceDestroyed()
                                }
                            })
                        }
                    }
                )

                if (!telemetry.isStreaming) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color(0xFF778DA9),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isDeviceReady) "Capture Card Ready" else "Waiting for USB Device",
                            color = Color(0xFFE0E1DD),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isDeviceReady) "Tap 'Start USB Video Capture' below" else "Plug in your capture card and grant permission",
                            color = Color(0xFF778DA9),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action button
            if (telemetry.isStreaming) {
                Button(
                    onClick = onStopCapture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("stop_capture_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop USB Video Capture", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartCapture,
                    enabled = isDeviceReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("start_capture_button")
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start USB Video Capture", fontWeight = FontWeight.Bold)
                }
            }

            if (telemetry.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Hardware note: ${telemetry.errorMessage}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TelemetryHudCard(telemetry: CaptureTelemetry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("telemetry_hud_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Milestone Verification Telemetry",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    label = "Incoming FPS",
                    value = if (telemetry.isStreaming) "${telemetry.fps}" else "0.0",
                    unit = "fps",
                    highlight = telemetry.isStreaming && telemetry.fps > 0f,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Total Frames",
                    value = "${telemetry.totalFrames}",
                    unit = "frames",
                    highlight = telemetry.totalFrames > 0L,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Resolution",
                    value = if (telemetry.resolutionWidth > 0) "${telemetry.resolutionWidth}x${telemetry.resolutionHeight}" else "--",
                    unit = telemetry.bufferFormat,
                    highlight = telemetry.resolutionWidth > 0,
                    modifier = Modifier.weight(1f)
                )
            }

            if (telemetry.totalFrames > 0L) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Milestone Verified: ${telemetry.bufferFormat} frames ingesting (${telemetry.totalFrames} frames received).",
                            color = Color(0xFF10B981),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun DiagnosticLogsSection(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostic_logs_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hardware Diagnostic Log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(onClick = onClearLogs) {
                    Text("Clear")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No log events yet",
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs, key = { it.id }) { log ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = log.timestamp,
                                    color = Color(0xFF64748B),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "[${log.tag}]",
                                    color = if (log.isError) Color(0xFFF87171) else Color(0xFF38BDF8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = log.message,
                                    color = if (log.isError) Color(0xFFFCA5A5) else Color(0xFFE2E8F0),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
