package com.example.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CaptureTelemetry
import com.example.model.LogEntry
import com.example.model.UsbDeviceInfo
import com.example.relay.TxRelayStats

import com.example.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxScreen(
    viewModel: CaptureViewModel,
    onChangeMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val localIp by viewModel.localIp.collectAsStateWithLifecycle()
    val txPort by viewModel.txPort.collectAsStateWithLifecycle()
    val connectedClients by viewModel.txConnectedClients.collectAsStateWithLifecycle()
    val isServerActive by viewModel.isTxServerActive.collectAsStateWithLifecycle()
    val txStats by viewModel.txStats.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val usbDevices by viewModel.connectedUsbDevices.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val isAudioStreaming by viewModel.isAudioStreaming.collectAsStateWithLifecycle()
    val isLocalAudioEnabled by viewModel.isLocalAudioEnabled.collectAsStateWithLifecycle()
    val isUsbAudioConnected by viewModel.isUsbAudioConnected.collectAsStateWithLifecycle()
    val activeAudioDeviceName by viewModel.activeAudioDeviceName.collectAsStateWithLifecycle()
    val selectedPreset by viewModel.selectedPreset.collectAsStateWithLifecycle()
    val isLocalPreviewEnabled by viewModel.isLocalPreviewEnabled.collectAsStateWithLifecycle()
    val isSend30FpsEnabled by viewModel.isSend30FpsEnabled.collectAsStateWithLifecycle()
    val connectedClientAddress by viewModel.txConnectedClientAddress.collectAsStateWithLifecycle()
    val publishedStreamName = viewModel.publishedStreamName
    val transportProtocol by viewModel.transportProtocol.collectAsStateWithLifecycle()
    val videoCodec by viewModel.videoCodec.collectAsStateWithLifecycle()
    val isBackgroundServiceArmed by viewModel.isBackgroundServiceArmed.collectAsStateWithLifecycle()

    val uvcDevice = usbDevices.firstOrNull { it.isUvcVideo } ?: usbDevices.firstOrNull()

    var showPortDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Stream Relay TX",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Transmitter · Port $txPort · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onChangeMode) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Switch mode"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshLocalIp() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh network IP"
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
            // Big Network IP Banner Card
            item {
                LocalIpBannerCard(
                    localIp = localIp,
                    port = txPort,
                    isServerActive = isServerActive,
                    connectedClients = connectedClients,
                    onEditPort = { showPortDialog = true }
                )
            }

            // Screen-Lock Background & Protocol Configuration
            item {
                TxStreamingConfigCard(
                    isBackgroundArmed = isBackgroundServiceArmed,
                    transportProtocol = transportProtocol,
                    videoCodec = videoCodec,
                    onSelectTransport = { viewModel.setTransportProtocol(it) },
                    onSelectCodec = { viewModel.setVideoCodec(it) }
                )
            }

            // Live Transmitter Preview Monitor Card (Side-by-side verification & 720p30 / 720p60 profile selection)
            item {
                TxPreviewMonitorCard(
                    isLocalPreviewEnabled = isLocalPreviewEnabled,
                    selectedPreset = selectedPreset,
                    telemetry = telemetry,
                    isSend30FpsEnabled = isSend30FpsEnabled,
                    onTogglePreview = { viewModel.setLocalPreviewEnabled(it) },
                    onToggleSend30Fps = { viewModel.setSend30FpsEnabled(it) },
                    onSelectPreset = { viewModel.selectPreset(it) },
                    availablePresets = viewModel.availablePresets,
                    onSurfaceHolderCreated = { viewModel.setLocalPreviewHolder(it) }
                )
            }

            // Client Connection Status Card
            item {
                ClientConnectionStatusCard(
                    connectedClients = connectedClients,
                    connectedClientAddress = connectedClientAddress,
                    publishedStreamName = publishedStreamName,
                    isServerActive = isServerActive
                )
            }

            // Capture Card Telemetry & Audio Status
            item {
                TxTelemetryCard(
                    telemetry = telemetry,
                    usbDevice = uvcDevice,
                    isAudioStreaming = isAudioStreaming,
                    isUsbAudioConnected = isUsbAudioConnected,
                    activeAudioDeviceName = activeAudioDeviceName,
                    isLocalAudioEnabled = isLocalAudioEnabled,
                    onToggleLocalAudio = { viewModel.setLocalAudioEnabled(it) },
                    onRequestUsbPermission = { vid, pid ->
                        viewModel.requestUsbPermission(vid, pid)
                    }
                )
            }

            // Requirement E: TX On-Screen Diagnostics Card
            item {
                TxRelayDiagnosticsCard(txStats = txStats)
            }

            // Hardware Diagnostic Logs
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

    if (showPortDialog) {
        var inputPort by remember { mutableStateOf(txPort.toString()) }
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("Set TCP Relay Port") },
            text = {
                OutlinedTextField(
                    value = inputPort,
                    onValueChange = { inputPort = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port Number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = inputPort.toIntOrNull()
                        if (parsed != null && parsed in 1024..65535) {
                            viewModel.setTxPort(parsed)
                        }
                        showPortDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun LocalIpBannerCard(
    localIp: String,
    port: Int,
    isServerActive: Boolean,
    connectedClients: Int,
    onEditPort: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TRANSMITTER IP ADDRESS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onEditPort,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit port",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Large text IP for user to easily read across room or copy
            Text(
                text = "$localIp:$port",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Zero-Config OMT LAN Discovery active • Or enter this IP address manually on receiver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClientConnectionStatusCard(
    connectedClients: Int,
    connectedClientAddress: String?,
    publishedStreamName: String,
    isServerActive: Boolean
) {
    val isClientActive = connectedClients > 0
    val tallyColor = if (isClientActive) Color(0xFFEF4444) else Color(0xFF10B981)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isClientActive) Color(0xFF450A0A).copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (isClientActive) 1.5.dp else 1.dp,
            if (isClientActive) Color(0xFFEF4444) else Color(0xFF10B981).copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(tallyColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isClientActive) "🔴 LIVE ON-AIR" else "📡 OMT BROADCASTING",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = tallyColor
                    )
                }

                Surface(
                    color = tallyColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isClientActive) "1 ACTIVE RECEIVER" else "READY FOR RX",
                        color = tallyColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (isClientActive && connectedClientAddress != null) {
                Text(
                    text = "Connected Receiver: $connectedClientAddress • Ultra-Low Latency Link",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFECACA),
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "Publishing OMT stream: \"$publishedStreamName\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Receivers on the same Wi-Fi discover this transmitter automatically via mDNS (_omt._tcp) & UDP :6399.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF38BDF8)
                )
            }
        }
    }
}

@Composable
private fun TxTelemetryCard(
    telemetry: CaptureTelemetry,
    usbDevice: UsbDeviceInfo?,
    isAudioStreaming: Boolean,
    isUsbAudioConnected: Boolean,
    activeAudioDeviceName: String,
    isLocalAudioEnabled: Boolean,
    onToggleLocalAudio: (Boolean) -> Unit,
    onRequestUsbPermission: (vendorId: Int, productId: Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Capture Card Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isReady = usbDevice?.hasPermission == true
                    val badgeColor = if (isReady) Color(0xFF10B981) else Color(0xFFF59E0B)
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isReady) "Capture Active" else "No Permission",
                            color = badgeColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val audioColor = if (isAudioStreaming) Color(0xFF10B981) else Color(0xFF64748B)
                    Surface(
                        color = audioColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isAudioStreaming) "Audio 48kHz" else "Audio Idle",
                            color = audioColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4-tile telemetry summary showing requested, negotiated, and measured FPS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    label = "Measured FPS",
                    value = if (telemetry.isStreaming) "${telemetry.fps}" else "0.0",
                    unit = "fps",
                    highlight = telemetry.isStreaming && telemetry.fps > 0f,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "FPS (Req/Neg)",
                    value = "${telemetry.requestedFps}/${telemetry.negotiatedFps}",
                    unit = "fps",
                    highlight = telemetry.negotiatedFps >= telemetry.requestedFps,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Resolution",
                    value = if (telemetry.resolutionWidth > 0) "${telemetry.resolutionWidth}x${telemetry.resolutionHeight}" else "720p",
                    unit = "MJPEG",
                    highlight = telemetry.resolutionWidth > 0,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Frame Size",
                    value = if (telemetry.lastFrameSizeBytes > 0) "${telemetry.lastFrameSizeBytes / 1024}" else "--",
                    unit = "KB",
                    highlight = telemetry.lastFrameSizeBytes > 0,
                    modifier = Modifier.weight(1f)
                )
            }

            if (telemetry.fpsNotice != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Notice: ${telemetry.fpsNotice}",
                        color = Color(0xFFF59E0B),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (usbDevice != null && !usbDevice.hasPermission) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onRequestUsbPermission(usbDevice.vendorId, usbDevice.productId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant USB Host Permission")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Audio Status & Anti-Howl Speaker Monitor Control
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isUsbAudioConnected) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = null,
                                tint = if (isUsbAudioConnected) Color(0xFF10B981) else Color(0xFFF59E0B),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Audio Input",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            color = if (isUsbAudioConnected) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isUsbAudioConnected) "USB HDMI Audio" else "Phone Mic",
                                color = if (isUsbAudioConnected) Color(0xFF10B981) else Color(0xFFF59E0B),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = activeAudioDeviceName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Transmitter Speaker Monitor",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isUsbAudioConnected) {
                                    if (isLocalAudioEnabled) "Monitoring USB HDMI audio through phone speaker"
                                    else "Muted by default to avoid echo in room"
                                } else {
                                    "Muted & blocked on phone mic to prevent howl-round feedback"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = isLocalAudioEnabled,
                            onCheckedChange = onToggleLocalAudio,
                            enabled = isUsbAudioConnected,
                            modifier = Modifier.testTag("tx_audio_monitor_switch")
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
    var showLogs by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        text = "TX Relay Logs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row {
                    TextButton(onClick = { showLogs = !showLogs }) {
                        Text(if (showLogs) "Collapse" else "Expand (${logs.size})")
                    }
                    if (showLogs) {
                        TextButton(onClick = onClearLogs) {
                            Text("Clear")
                        }
                    }
                }
            }

            if (showLogs) {
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
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
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            logs.take(50).forEach { log ->
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
}

@Composable
private fun TxRelayDiagnosticsCard(
    txStats: TxRelayStats
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    text = "Relay Network Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = if (txStats.isClientConnected) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF64748B).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (txStats.isClientConnected) "Client Connected: YES" else "Client Connected: NO",
                        color = if (txStats.isClientConnected) Color(0xFF10B981) else Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Monospace diagnostics lines (Requirement E)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Frames Captured: ${txStats.framesCaptured}  |  Submitted: ${txStats.framesSubmitted}",
                        color = Color(0xFF38BDF8),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Frames Sent: ${txStats.framesSent}  |  Dropped: ${txStats.framesDropped}",
                        color = if (txStats.framesDropped > 0) Color(0xFFFDE68A) else Color(0xFFA7F3D0),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Last Frame: ${txStats.lastFrameSizeBytes} B (${txStats.lastFrameSizeBytes / 1024} KB)",
                        color = Color(0xFFE2E8F0),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Starts FF D8: ${if (txStats.lastFrameStartsSoi) "YES" else "NO"}  |  Ends FF D9: ${if (txStats.lastFrameEndsEoi) "YES" else "NO"}",
                        color = if (txStats.lastFrameStartsSoi && txStats.lastFrameEndsEoi) Color(0xFF34D399) else Color(0xFFF87171),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Audio Chunks Captured: ${txStats.audioChunksCaptured}  |  Sent: ${txStats.audioChunksSent}",
                        color = Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun TxPreviewMonitorCard(
    isLocalPreviewEnabled: Boolean,
    selectedPreset: com.example.model.StreamPreset,
    telemetry: com.example.model.CaptureTelemetry,
    isSend30FpsEnabled: Boolean,
    onTogglePreview: (Boolean) -> Unit,
    onToggleSend30Fps: (Boolean) -> Unit,
    onSelectPreset: (com.example.model.StreamPreset) -> Unit,
    availablePresets: List<com.example.model.StreamPreset>,
    onSurfaceHolderCreated: (SurfaceHolder?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header with toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isLocalPreviewEnabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = if (isLocalPreviewEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Column {
                        Text(
                            text = "Transmitter Preview Monitor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isLocalPreviewEnabled) "Live hardware monitor active" else "Preview paused (transmitting in background)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = isLocalPreviewEnabled,
                    onCheckedChange = onTogglePreview,
                    modifier = Modifier.testTag("tx_preview_switch")
                )
            }

            // Stream Resolution & FPS Presets (Select 720p30 vs 720p60)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Profile:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                FilterChip(
                    selected = selectedPreset.id == "mjpeg_720p_30",
                    onClick = {
                        val p = availablePresets.firstOrNull { it.id == "mjpeg_720p_30" }
                        if (p != null) onSelectPreset(p)
                    },
                    label = { Text("720p30") },
                    modifier = Modifier.testTag("preset_720p30_chip"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                        selectedLabelColor = Color(0xFF10B981)
                    )
                )

                FilterChip(
                    selected = selectedPreset.id == "mjpeg_720p_60",
                    onClick = {
                        val p = availablePresets.firstOrNull { it.id == "mjpeg_720p_60" }
                        if (p != null) onSelectPreset(p)
                    },
                    label = { Text("720p60") },
                    modifier = Modifier.testTag("preset_720p60_chip"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )

                FilterChip(
                    selected = selectedPreset.id == "mjpeg_1080p_30",
                    onClick = {
                        val p = availablePresets.firstOrNull { it.id == "mjpeg_1080p_30" }
                        if (p != null) onSelectPreset(p)
                    },
                    label = { Text("1080p30") },
                    modifier = Modifier.testTag("preset_1080p30_chip")
                )
            }

            // Send 30 fps (lower latency) toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Send 30 fps (lower latency)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Halves network frame rate without changing camera capture",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = isSend30FpsEnabled,
                    onCheckedChange = onToggleSend30Fps,
                    modifier = Modifier.testTag("tx_send_30_fps_switch")
                )
            }

            // Video Preview Viewport
            if (isLocalPreviewEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) {
                                        onSurfaceHolderCreated(h)
                                    }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                                        onSurfaceHolderCreated(h)
                                    }
                                    override fun surfaceDestroyed(h: SurfaceHolder) {
                                        onSurfaceHolderCreated(null)
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Minimal status overlay in corner
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = "${if (telemetry.isStreaming) telemetry.fps else 0f} fps · ${selectedPreset.label}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TxStreamingConfigCard(
    isBackgroundArmed: Boolean,
    transportProtocol: com.example.ui.TransportProtocol,
    videoCodec: com.example.ui.VideoCodec,
    onSelectTransport: (com.example.ui.TransportProtocol) -> Unit,
    onSelectCodec: (com.example.ui.VideoCodec) -> Unit
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Background screen-off status badge
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (isBackgroundArmed) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (isBackgroundArmed) Color(0xFF10B981).copy(alpha = 0.5f) else Color.Transparent)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isBackgroundArmed) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (isBackgroundArmed) Color(0xFF10B981) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isBackgroundArmed) "Screen-Lock Streaming Armed" else "Screen-Lock Service Idle",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isBackgroundArmed) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isBackgroundArmed) "Safe to turn off screen to keep phone cool. CPU wake lock & Wi-Fi lock active."
                            else "Service starts automatically when streaming begins.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Transport Protocol Selection (TCP vs UDP)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Transport Protocol",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.example.ui.TransportProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = transportProtocol == proto,
                            onClick = { onSelectTransport(proto) },
                            label = { Text(proto.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Video Codec Selection (MJPEG vs H.264)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Video Encoding",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.example.ui.VideoCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = videoCodec == codec,
                            onClick = { onSelectCodec(codec) },
                            label = { Text(codec.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}


