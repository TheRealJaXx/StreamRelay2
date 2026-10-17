package com.example.ui

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.relay.AudioLatencyProfile
import com.example.relay.RxConnectionState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxScreen(
    viewModel: CaptureViewModel,
    onChangeMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionState by viewModel.rxConnectionState.collectAsStateWithLifecycle()
    val stats by viewModel.rxStats.collectAsStateWithLifecycle()
    val targetIp by viewModel.rxTargetIp.collectAsStateWithLifecycle()
    val targetPort by viewModel.rxTargetPort.collectAsStateWithLifecycle()
    val isAudioPlaying by viewModel.isAudioPlaying.collectAsStateWithLifecycle()
    val isRxMuted by viewModel.isRxMuted.collectAsStateWithLifecycle()
    val audioProfile by viewModel.audioLatencyProfile.collectAsStateWithLifecycle()
    val audioDelayMs by viewModel.audioDelayMs.collectAsStateWithLifecycle()

    var inputIp by remember(targetIp) { mutableStateOf(targetIp) }
    var inputPort by remember(targetPort) { mutableStateOf(targetPort.toString()) }

    // Toggle overlay on screen tap
    var showOverlay by remember { mutableStateOf(true) }

    // Auto-hide overlay after 4 seconds of active connected streaming
    LaunchedEffect(showOverlay, connectionState, stats.videoPacketsReceived) {
        if (showOverlay && connectionState == RxConnectionState.CONNECTED && stats.videoPacketsReceived > 30) {
            delay(4000)
            showOverlay = false
        }
    }

    // Presentation requirements: Immersive sticky, cutout mode SHORT_EDGES, keep screen on, low latency Wi-Fi Lock
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // API 28+ Display Cutout SHORT_EDGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes?.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Immersive sticky (transient on swipe)
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        // Low latency Wi-Fi Lock while RxScreen is visible
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val wifiLock = wm?.createWifiLock(wifiMode, "StreamRelay:RxWifiLock")?.apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            try {
                if (wifiLock?.isHeld == true) wifiLock.release()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showOverlay = !showOverlay
            }
    ) {
        // 1. ISOLATED VIDEO VIEWPORT: Direct SurfaceView rendering with zero recomposition
        RxVideoFeed(
            viewModel = viewModel,
            connectionState = connectionState,
            statusDetail = stats.statusDetail,
            videoPacketsReceived = stats.videoPacketsReceived,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Animated UI Overlay (Top header, Diagnostics, and Bottom Controls)
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(16.dp)
            ) {
                // Top Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1E293B).copy(alpha = 0.85f)
                        ) {
                            IconButton(onClick = onChangeMode) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Switch mode",
                                    tint = Color.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Stream Relay RX",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Receiver · v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    // Connection Badge
                    val (badgeText, badgeBg, badgeFg) = when (connectionState) {
                        RxConnectionState.CONNECTED -> Triple("CONNECTED", Color(0xFF10B981).copy(alpha = 0.2f), Color(0xFF10B981))
                        RxConnectionState.CONNECTING -> Triple("CONNECTING...", Color(0xFFF59E0B).copy(alpha = 0.2f), Color(0xFFF59E0B))
                        RxConnectionState.RECONNECTING -> Triple("RETRYING", Color(0xFFEF4444).copy(alpha = 0.2f), Color(0xFFEF4444))
                        RxConnectionState.DISCONNECTED -> Triple("OFFLINE", Color(0xFF64748B).copy(alpha = 0.2f), Color(0xFF94A3B8))
                    }

                    Surface(
                        color = badgeBg,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            if (connectionState == RxConnectionState.CONNECTING || connectionState == RxConnectionState.RECONNECTING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = badgeFg
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = badgeText,
                                color = badgeFg,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Diagnostics Panel (FPS, Decode avg/max, Frame age, RTT avg/p95, Dropped, Audio)
                if (connectionState != RxConnectionState.DISCONNECTED || stats.videoPacketsReceived > 0L) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 64.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.92f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Line 1: Primary FPS & Latencies
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "FPS: ${stats.fps}",
                                    color = Color(0xFF38BDF8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Decode: ${stats.decodeTimeMsAvg}ms (max: ${stats.decodeTimeMsMax}ms)",
                                    color = Color(0xFFA7F3D0),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Age: ${stats.frameAgeMs}ms",
                                    color = Color(0xFFFDE68A),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Line 2: Real Network RTT (Probe port P+2) & Interval
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "RTT: ${stats.rttAvgMs}ms (p95: ${stats.rttP95Ms}ms)",
                                    color = Color(0xFF34D399),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Arrival p95: ${stats.arrivalIntervalP95Ms}ms",
                                    color = Color(0xFFCBD5E1),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Dropped: ${stats.droppedFrames}",
                                    color = if (stats.droppedFrames > 0) Color(0xFFF87171) else Color(0xFF94A3B8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Line 3: Packet Counters & Throughput
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Video Pkts: ${stats.videoPacketsReceived}",
                                    color = Color(0xFFE2E8F0),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${stats.videoBytesPerSecond / 1024} kB/s",
                                    color = Color(0xFF38BDF8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Audio Pkts: ${stats.audioPacketsReceived}",
                                    color = Color(0xFFE2E8F0),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Underruns: ${stats.audioUnderrunCount}",
                                    color = if (stats.audioUnderrunCount > 0) Color(0xFFF59E0B) else Color(0xFF94A3B8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Line 4: Audio Mute & Structure
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { viewModel.setRxMuted(!isRxMuted) }
                                ) {
                                    Text(
                                        text = if (isRxMuted) "Audio: MUTED" else if (isAudioPlaying) "Audio: LIVE" else "Audio: Wait",
                                        color = if (isRxMuted) Color(0xFFF87171) else if (isAudioPlaying) Color(0xFF34D399) else Color(0xFF94A3B8),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "Head: [${stats.lastFrameFirst4BytesHex}]",
                                    color = Color(0xFFCBD5E1),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Res: ${stats.decodedBitmapSize}",
                                    color = Color(0xFF38BDF8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Line 5: Status detail
                            if (stats.statusDetail.isNotBlank() && stats.statusDetail != "Connected") {
                                Text(
                                    text = "Status: ${stats.statusDetail}",
                                    color = if (stats.statusDetail.contains("refused") || stats.statusDetail.contains("timed out") || stats.statusDetail.contains("mismatch")) Color(0xFFF87171) else Color(0xFF94A3B8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }

                // Bottom Control Card (IP, Port, Audio profile, Audio delay, Connect/Disconnect)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF0F172A).copy(alpha = 0.94f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // IP & Port Fields
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = inputIp,
                                onValueChange = { inputIp = it },
                                label = { Text("Transmitter IP Address", color = Color(0xFF94A3B8)) },
                                modifier = Modifier.weight(2f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )

                            OutlinedTextField(
                                value = inputPort,
                                onValueChange = { inputPort = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Port", color = Color(0xFF94A3B8)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )
                        }

                        // Audio Latency Profile Selector (Task 7)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Audio Profile:",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Bold
                            )
                            AudioLatencyProfile.entries.forEach { profile ->
                                FilterChip(
                                    selected = audioProfile == profile,
                                    onClick = { viewModel.setAudioLatencyProfile(profile) },
                                    label = { Text(profile.label, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }

                        // Audio Delay Slider (0 to 200 ms) (Task 7)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Audio Delay: ${audioDelayMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.width(115.dp)
                            )
                            Slider(
                                value = audioDelayMs.toFloat(),
                                onValueChange = { viewModel.setAudioDelayMs(it.toInt()) },
                                valueRange = 0f..200f,
                                steps = 19,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Connect / Disconnect Action Button
                        if (connectionState == RxConnectionState.CONNECTED || connectionState == RxConnectionState.CONNECTING || connectionState == RxConnectionState.RECONNECTING) {
                            Button(
                                onClick = { viewModel.disconnectRx() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Disconnect", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    val port = inputPort.toIntOrNull() ?: 4120
                                    viewModel.setRxTarget(inputIp, port)
                                    viewModel.connectRx()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("connect_rx_button")
                            ) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connect to Transmitter", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Direct Hardware-Accelerated Video Rendering Composable.
 * Uses a native SurfaceView with direct hardware canvas lock/unlock via MjpegPlayer.
 * Synchronizes display refresh rate on API 30+ to eliminate judder on 90/120 Hz displays.
 */
@Composable
internal fun RxVideoFeed(
    viewModel: CaptureViewModel,
    connectionState: RxConnectionState,
    statusDetail: String,
    videoPacketsReceived: Long,
    modifier: Modifier = Modifier
) {
    val isReceiving = connectionState == RxConnectionState.CONNECTED && videoPacketsReceived > 0L

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    isClickable = false
                    isFocusable = false
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                                } catch (_: Exception) {}
                            }
                            viewModel.attachRxSurface(holder)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                                } catch (_: Exception) {}
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.detachRxSurface(holder)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Idle placeholder when disconnected or waiting for first frame
        if (!isReceiving) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.90f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = Color(0xFF475569),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when (connectionState) {
                        RxConnectionState.CONNECTING -> statusDetail
                        RxConnectionState.RECONNECTING -> statusDetail
                        RxConnectionState.CONNECTED -> if (videoPacketsReceived == 0L) statusDetail else "Waiting for video frame..."
                        RxConnectionState.DISCONNECTED -> "Receiver Ready"
                    },
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
